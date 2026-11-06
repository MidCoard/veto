package top.focess.veto.veto;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.*;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import top.focess.veto.api.llm.LocalModelRuntime;

/** Host-owned llama.cpp runtime shared by file-backed providers and core grammar inference. */
@Component
public class LlamaCppBridge implements LocalModelRuntime {

    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.veto.LlamaCppBridge");

    private final @NonNull SlmConfiguration config;
    private final @NonNull GBNFGrammarEngine grammarEngine;

    private volatile Process llamaProcess;
    private Thread outputReader;
    private volatile int serverPort = -1;
    private final @NonNull Map<@NonNull ModelInstance, @NonNull LlamaCppBridge> models =
            new HashMap<>();

    private record ModelInstance(@NonNull Path path, int contextTokens) {}

    /** Opens a filesystem artifact through the same runtime used for core intent inference. */
    @Override
    public synchronized @NonNull String endpoint(@NonNull String modelPath, int contextTokens) {
        if (contextTokens < 512 || contextTokens > 131072)
            throw new IllegalArgumentException("Invalid local model context size");
        if (modelPath.contains("://"))
            throw new IllegalArgumentException("Local model endpoint must be a filesystem path");
        Path path;
        try {
            path = config.resolvePath(modelPath).toRealPath();
        } catch (IOException failure) {
            throw new IllegalArgumentException("Local model file unavailable", failure);
        }
        var filename = path.getFileName();
        if (!Files.isRegularFile(path)
                || filename == null
                || !filename.toString().endsWith(".gguf"))
            throw new IllegalArgumentException("Local model endpoint must be a GGUF file");
        LlamaCppBridge bridge;
        if (isPrimaryArtifact(path) && contextTokens == config.getNCtx()) {
            bridge = this;
        } else {
            var key = new ModelInstance(path, contextTokens);
            bridge =
                    models.computeIfAbsent(
                            key,
                            ignored -> {
                                var settings = config.forModel(path.toString(), contextTokens);
                                return new LlamaCppBridge(
                                        settings, new GBNFGrammarEngine(settings));
                            });
        }
        if (!bridge.start()) throw new IllegalStateException("Local model unavailable");
        return "http://127.0.0.1:" + bridge.serverPort + "/v1";
    }

    private boolean isPrimaryArtifact(@NonNull Path path) {
        try {
            return Files.isSameFile(path, config.resolvePath(config.getModelPath()));
        } catch (IOException unavailable) {
            return false;
        }
    }

    private final @NonNull HttpClient httpClient =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    /** Creates the bridge; the llama.cpp subprocess is started lazily by {@link #start()}. */
    public LlamaCppBridge(
            @NonNull SlmConfiguration config, @NonNull GBNFGrammarEngine grammarEngine) {
        this.config = config;
        this.grammarEngine = grammarEngine;
    }

    /** Starts llama.cpp with native chat templates. Completion calls supply their own grammar. */
    @PostConstruct
    public synchronized boolean start() {
        if (isAvailable()) {
            return true;
        }
        if (llamaProcess != null) stopMain();

        if (!config.isEnabled()) {
            log.info("Local SLM is disabled by veto.slm.enabled");
            return false;
        }

        String modelPath = config.getModelPath();
        Path resolved = config.resolvePath(modelPath);

        if (!Files.exists(resolved)) {
            log.warn(
                    "gateway LlamaCpp: Model not found at '{}'. SLM inference disabled. "
                            + "Place a quantized GGUF model (1B-3B) at this path.",
                    resolved);
            return false;
        }

        try {
            // Use a fixed port for reliability (0 = random, but we need to discover it)
            int port = findAvailablePort();

            ProcessBuilder pb = llamaServerProcess(resolved.toString(), port);

            pb.redirectErrorStream(true);
            Process process = pb.start();
            llamaProcess = process;
            drainProcessOutput(process);
            waitUntilListening(process, port);
            this.serverPort = port;

            log.info(
                    "gateway LlamaCpp: Started llama.cpp with model '{}' (ctx={}, temp={}, port={})",
                    resolved,
                    config.getNCtx(),
                    config.getTemperature(),
                    serverPort);

            return true;

        } catch (IOException | InterruptedException failure) {
            if (failure instanceof InterruptedException) {
                log.info("gateway LlamaCpp: Startup interrupted");
            } else
                log.error("gateway LlamaCpp: Failed to start llama.cpp. Is it installed?", failure);
            stopMain();
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            return false;
        }
    }

    private @NonNull ProcessBuilder llamaServerProcess(@NonNull String modelPath, int port) {
        return new ProcessBuilder(
                resolveExecutablePath(),
                "--model",
                modelPath,
                "--ctx-size",
                String.valueOf(config.getNCtx()),
                "--n-gpu-layers",
                String.valueOf(config.getNGpuLayers()),
                "--temp",
                String.valueOf(config.getTemperature()),
                "--jinja",
                "--port",
                String.valueOf(port),
                "--cont-batching");
    }

    /** Uses an explicitly configured executable, PATH, or the ignored local runtime directory. */
    private @NonNull String resolveExecutablePath() {
        String configured = config.getExecutablePath();
        if (!"llama-server".equals(configured)) {
            return configured.indexOf('/') >= 0 || configured.indexOf('\\') >= 0
                    ? config.resolvePath(configured).toString()
                    : configured;
        }
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        Path local =
                config.resolvePath(
                        Path.of(
                                        "models",
                                        "llama.cpp",
                                        windows ? "llama-server.exe" : "llama-server")
                                .toString());
        return Files.isRegularFile(local) ? local.toString() : configured;
    }

    /**
     * Perform SLM inference with grammar-constrained output via the HTTP API. Returns the model's
     * structured response.
     */
    public @NonNull CompletableFuture<String> infer(
            @NonNull String prompt, @NonNull String grammarName) {
        if (!isAvailable()) {
            return CompletableFuture.completedFuture(
                    "{\"veto_decision\":\"pass\",\"data\":{\"note\":\"SLM unavailable, passed without analysis\"}}");
        }

        String grammar = grammarEngine.resolveGrammar(grammarName);
        var transport = requestCompletion(prompt, grammar, Duration.ofSeconds(30));
        var result =
                transport.handle(
                        (response, failure) -> {
                            if (failure != null) {
                                log.error("gateway LlamaCpp: Inference failed", failure);
                                return "{\"veto_decision\":\"pass\",\"data\":{\"error\":\""
                                        + escapeJson(failure.getMessage())
                                        + "\"}}";
                            } else if (response.statusCode() != 200) {
                                log.warn(
                                        "gateway LlamaCpp: Inference returned status {}",
                                        response.statusCode());
                                return "{\"veto_decision\":\"pass\",\"data\":{\"note\":\"SLM inference error\"}}";
                            } else return extractContentFromResponse(response.body());
                        });
        result.whenComplete(
                (value, failure) -> {
                    if (result.isCancelled())
                        log.debug(
                                "Local inference HTTP request cancelled: {}",
                                transport.cancel(true));
                });
        return result;
    }

    private @NonNull CompletableFuture<@NonNull HttpResponse<@NonNull String>> requestCompletion(
            @NonNull String prompt, @NonNull String grammar, @NonNull Duration timeout) {
        String body =
                String.format(
                        "{\"prompt\":\"%s\",\"n_predict\":512,\"temperature\":%s,\"stop\":[\"###\"],\"grammar\":\"%s\"}",
                        escapeJson(prompt), config.getTemperature(), escapeJson(grammar));
        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(
                                URI.create(
                                        String.format(
                                                "http://127.0.0.1:%d/v1/completions", serverPort)))
                        .timeout(timeout)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
        try {
            return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    /** Stops owned model processes and joins their output readers before returning. */
    @PreDestroy
    public synchronized void stop() {
        models.values().forEach(LlamaCppBridge::stop);
        models.clear();
        stopMain();
    }

    private void stopMain() {
        serverPort = -1;
        Process process = llamaProcess;
        llamaProcess = null;
        if (process != null && process.isAlive()) {
            process.destroy();
            try {
                process.waitFor(5, TimeUnit.SECONDS);
                if (process.isAlive()) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                process.destroyForcibly();
            }
        }
        while (outputReader != null) {
            try {
                outputReader.join();
                outputReader = null;
            } catch (InterruptedException ignored) {
                // Shutdown has already stopped the producer; finish joining the owned reader.
            }
        }
        log.info("gateway LlamaCpp: Stopped");
    }

    public boolean isAvailable() {
        Process process = llamaProcess;
        return serverPort > 0 && process != null && process.isAlive();
    }

    // ── Internal helpers ──

    /** Find an available port for llama-server. */
    private int findAvailablePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @SuppressWarnings("BusyWait") // Bounded readiness polling with interruptible backoff.
    private void waitUntilListening(@NonNull Process process, int port)
            throws IOException, InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (true) {
            if (!process.isAlive())
                throw new IOException("llama-server exited before opening its HTTP port");
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) break;
            try {
                HttpRequest request =
                        HttpRequest.newBuilder()
                                .uri(URI.create("http://127.0.0.1:" + port + "/health"))
                                .timeout(
                                        Duration.ofNanos(
                                                Math.min(
                                                        remaining,
                                                        TimeUnit.MILLISECONDS.toNanos(500))))
                                .GET()
                                .build();
                var response =
                        Objects.requireNonNull(
                                httpClient.send(request, HttpResponse.BodyHandlers.discarding()),
                                "HttpClient.send returned null");
                if (response.statusCode() == 200) return;
            } catch (IOException notReady) {
                // Retry connection and response failures until the process is ready or exits.
            }
            remaining = deadline - System.nanoTime();
            if (remaining <= 0) break;
            TimeUnit.NANOSECONDS.sleep(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(100)));
        }
        throw new IOException("llama-server did not open its HTTP port within 30 seconds");
    }

    private void drainProcessOutput(@NonNull Process process) {
        // Native process-pipe reads pin virtual-thread carriers on Windows.
        outputReader =
                Thread.ofPlatform()
                        .name("veto-llamacpp-output")
                        .start(
                                () -> {
                                    try (BufferedReader reader =
                                            new BufferedReader(
                                                    new InputStreamReader(
                                                            process.getInputStream()))) {
                                        String line;
                                        while ((line = reader.readLine()) != null) {
                                            log.debug("gateway llama-server: {}", line);
                                        }
                                    } catch (IOException e) {
                                        if (process.isAlive()) {
                                            log.debug(
                                                    "gateway llama-server output stream closed", e);
                                        }
                                    }
                                });
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
