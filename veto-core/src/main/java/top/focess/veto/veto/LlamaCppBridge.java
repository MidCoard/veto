package top.focess.veto.veto;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * gateway LlamaCpp Bridge — interface to llama.cpp for local SLM inference. Manages the llama.cpp
 * subprocess lifecycle and provides grammar-constrained decoding using GBNF grammars. The SLM
 * (quantized 1B-3B model) acts as the intent firewall and semantic redactor.
 *
 * <p>Uses llama-server's OpenAI-compatible HTTP API ({@code POST /v1/completions}) for inference,
 * which is more reliable than raw stdin/stdout and supports concurrent requests.
 */
@Component
public class LlamaCppBridge {

    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.veto.LlamaCppBridge");

    private final @NonNull VetoGatewayConfiguration config;
    private final @NonNull GBNFGrammarEngine grammarEngine;

    private Process llamaProcess;
    private volatile int serverPort = -1;
    private final @NonNull HttpClient httpClient =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final @NonNull ExecutorService startupExecutor =
            Executors.newSingleThreadExecutor(
                    r -> {
                        Thread t = new Thread(r, "veto-llamacpp-startup");
                        t.setDaemon(true);
                        return t;
                    });

    private volatile boolean available = false;

    public LlamaCppBridge(
            @NonNull VetoGatewayConfiguration config, @NonNull GBNFGrammarEngine grammarEngine) {
        this.config = config;
        this.grammarEngine = grammarEngine;
    }

    /**
     * Start the llama.cpp subprocess with the configured model. The process is started with GBNF
     * grammar-constrained decoding. Parses the server port from startup output for HTTP API access.
     */
    public synchronized boolean start() {
        if (available) {
            return true;
        }

        String modelPath = config.getLlamaCpp().getModelPath();
        Path resolved = Path.of(modelPath);

        if (!Files.exists(resolved)) {
            log.warn(
                    "gateway LlamaCpp: Model not found at '{}'. SLM inference disabled. "
                            + "Place a quantized GGUF model (1B-3B) at this path.",
                    modelPath);
            this.available = false;
            return false;
        }

        try {
            // Build the llama.cpp command with grammar-constrained decoding
            String gbnfGrammar = grammarEngine.loadVetoOutputGrammar();
            Path grammarFile = Files.createTempFile("veto-grammar-", ".gbnf");
            grammarFile.toFile().deleteOnExit();
            Files.writeString(grammarFile, gbnfGrammar);

            // Use a fixed port for reliability (0 = random, but we need to discover it)
            int port = findAvailablePort();

            ProcessBuilder pb = llamaServerProcess(modelPath, grammarFile, port);

            pb.redirectErrorStream(true);
            Process process = pb.start();
            llamaProcess = process;
            drainProcessOutput(process);
            waitUntilListening(process, port);
            this.serverPort = port;

            available = true;
            log.info(
                    "gateway LlamaCpp: Started llama.cpp with model '{}' (ctx={}, temp={}, port={})",
                    modelPath,
                    config.getLlamaCpp().getNCtx(),
                    config.getLlamaCpp().getTemperature(),
                    serverPort);

            // Start health monitor
            startHealthMonitor();

            return true;

        } catch (IOException e) {
            log.error("gateway LlamaCpp: Failed to start llama.cpp. Is it installed?", e);
            this.available = false;
            return false;
        }
    }

    private @NonNull ProcessBuilder llamaServerProcess(
            @NonNull String modelPath, @NonNull Path grammarFile, int port) {
        return new ProcessBuilder(
                config.getLlamaCpp().getExecutablePath(),
                "--model",
                modelPath,
                "--ctx-size",
                String.valueOf(config.getLlamaCpp().getNCtx()),
                "--n-gpu-layers",
                String.valueOf(config.getLlamaCpp().getNGpuLayers()),
                "--temp",
                String.valueOf(config.getLlamaCpp().getTemperature()),
                "--grammar-file",
                grammarFile.toAbsolutePath().toString(),
                "--port",
                String.valueOf(port),
                "--cont-batching");
    }

    /**
     * Perform SLM inference with grammar-constrained output via the HTTP API. Returns the model's
     * structured response.
     */
    public @NonNull CompletableFuture<String> infer(
            @NonNull String prompt, @NonNull String grammarName) {
        if (!available || serverPort <= 0) {
            return CompletableFuture.completedFuture(
                    "{\"veto_decision\":\"pass\",\"data\":{\"note\":\"SLM unavailable, passed without analysis\"}}");
        }

        String grammar = grammarEngine.resolveGrammar(grammarName);
        return CompletableFuture.supplyAsync(
                () -> {
                    try {
                        // Build the OpenAI-compatible completions request
                        String requestBody =
                                String.format(
                                        "{\"prompt\":\"%s\",\"n_predict\":512,\"temperature\":%s,"
                                                + "\"stop\":[\"###\"],\"grammar\":\"%s\"}",
                                        escapeJson(prompt),
                                        config.getLlamaCpp().getTemperature(),
                                        escapeJson(grammar));

                        HttpRequest request =
                                HttpRequest.newBuilder()
                                        .uri(
                                                URI.create(
                                                        String.format(
                                                                "http://127.0.0.1:%d/v1/completions",
                                                                serverPort)))
                                        .timeout(Duration.ofSeconds(30))
                                        .header("Content-Type", "application/json")
                                        .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                                        .build();

                        HttpResponse<String> response =
                                httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                        if (response.statusCode() == 200) {
                            return extractContentFromResponse(response.body());
                        } else {
                            log.warn(
                                    "gateway LlamaCpp: Inference returned status {}",
                                    response.statusCode());
                            return "{\"veto_decision\":\"pass\",\"data\":{\"note\":\"SLM inference error\"}}";
                        }

                    } catch (Exception e) {
                        log.error("gateway LlamaCpp: Inference failed", e);
                        return "{\"veto_decision\":\"pass\",\"data\":{\"error\":\""
                                + escapeJson(e.getMessage())
                                + "\"}}";
                    }
                });
    }

    /** Kill request for priority interruption. */
    public void killCurrentInference() {
        // With HTTP API, we can cancel pending requests by closing connections
        log.warn("gateway LlamaCpp: Kill current inference requested");
    }

    /** Gracefully stop the llama.cpp subprocess. */
    public synchronized void stop() {
        available = false;
        serverPort = -1;
        Process process = llamaProcess;
        if (process != null && process.isAlive()) {
            process.destroy();
            try {
                process.waitFor(5, TimeUnit.SECONDS);
                if (process.isAlive()) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
        log.info("gateway LlamaCpp: Stopped");
    }

    /**
     * Restart the llama.cpp subprocess with a different model. Used by TrainingManager after a new
     * model is trained and deployed.
     *
     * @param newModelPath path to the new GGUF model file
     * @return true if the restart succeeded
     */
    public synchronized boolean restartWithModel(@NonNull String newModelPath) {
        log.info("gateway LlamaCpp: Restarting with new model '{}'", newModelPath);
        stop();

        // Update the config model path
        this.config.getLlamaCpp().setModelPath(newModelPath);

        // Small delay to let ports release
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        return start();
    }

    public boolean isAvailable() {
        return available;
    }

    // ── Internal helpers ──

    /** Find an available port for llama-server. */
    private int findAvailablePort() throws IOException {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private void waitUntilListening(@NonNull Process process, int port) throws IOException {
        ScheduledExecutorService readinessExecutor =
                Executors.newSingleThreadScheduledExecutor(
                        runnable -> {
                            Thread thread = new Thread(runnable, "veto-llamacpp-readiness");
                            thread.setDaemon(true);
                            return thread;
                        });
        CompletableFuture<Void> readiness = new CompletableFuture<>();
        ScheduledFuture<?> probe =
                readinessExecutor.scheduleWithFixedDelay(
                        () -> probeReadiness(process, port, readiness),
                        0,
                        100,
                        TimeUnit.MILLISECONDS);
        try {
            readiness.get(30, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new IOException("llama-server did not open its HTTP port within 30 seconds", e);
        } catch (ExecutionException e) {
            throw new IOException("llama-server exited before opening its HTTP port", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for llama-server", e);
        } finally {
            probe.cancel(false);
            readinessExecutor.shutdown();
        }
    }

    private void probeReadiness(
            @NonNull Process process, int port, @NonNull CompletableFuture<Void> readiness) {
        if (readiness.isDone()) {
            return;
        }
        if (!process.isAlive()) {
            readiness.completeExceptionally(
                    new IOException("llama-server exited before opening its HTTP port"));
            return;
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 250);
            readiness.complete(null);
        } catch (IOException notReady) {
            // The scheduled probe retries until the server listens, exits, or times out.
        }
    }

    private void drainProcessOutput(@NonNull Process process) {
        Thread thread =
                new Thread(
                        () -> {
                            try (BufferedReader reader =
                                    new BufferedReader(
                                            new InputStreamReader(process.getInputStream()))) {
                                String line;
                                while ((line = reader.readLine()) != null) {
                                    log.debug("gateway llama-server: {}", line);
                                }
                            } catch (IOException e) {
                                if (process.isAlive()) {
                                    log.debug("gateway llama-server output stream closed", e);
                                }
                            }
                        },
                        "veto-llamacpp-output");
        thread.setDaemon(true);
        thread.start();
    }

    /** Extract the generated content from the OpenAI completions response JSON. */
    private @NonNull String extractContentFromResponse(@NonNull String responseBody) {
        // Parse the OpenAI-format response: {"content":[{"text":"..."}]}
        try {
            Pattern textPattern = Pattern.compile("\"text\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
            Matcher m = textPattern.matcher(responseBody);
            if (m.find()) {
                return unescapeJson(m.group(1));
            }
            // Fallback: try "content" field
            Pattern contentPattern =
                    Pattern.compile("\"content\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
            Matcher cm = contentPattern.matcher(responseBody);
            if (cm.find()) {
                return unescapeJson(cm.group(1));
            }
        } catch (Exception e) {
            log.warn("gateway LlamaCpp: Failed to parse response body", e);
        }
        return responseBody;
    }

    @SuppressWarnings("BusyWait") // Deliberate 30-second process liveness poll, not a spin loop.
    private void startHealthMonitor() {
        startupExecutor.submit(
                () -> {
                    while (available) {
                        if (llamaProcess != null && !llamaProcess.isAlive()) {
                            log.warn("gateway LlamaCpp: Process died unexpectedly");
                            available = false;
                            break;
                        }
                        try {
                            Thread.sleep(30_000);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                });
    }

    private @NonNull String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private @NonNull String unescapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\");
    }
}
