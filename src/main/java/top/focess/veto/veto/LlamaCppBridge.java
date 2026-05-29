package top.focess.veto.veto;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.*;

/**
 * C7 LlamaCpp Bridge �?interface to llama.cpp for local SLM inference.
 * Manages the llama.cpp subprocess lifecycle and provides grammar-constrained decoding
 * using GBNF grammars. The SLM (quantized 1B-3B model) acts as the intent firewall
 * and semantic redactor.
 */
@Component
public class LlamaCppBridge {

    private static final Logger log = LoggerFactory.getLogger(LlamaCppBridge.class);

    private final VetoGatewayConfiguration config;
    private final GBNFGrammarEngine grammarEngine;

    private Process llamaProcess;
    private PrintWriter processInput;
    private BufferedReader processOutput;
    private Path grammarTempFile;
    private final ExecutorService inferenceExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "veto-llamacpp");
        t.setDaemon(true);
        return t;
    });

    private volatile boolean available = false;

    public LlamaCppBridge(VetoGatewayConfiguration config, GBNFGrammarEngine grammarEngine) {
        this.config = config;
        this.grammarEngine = grammarEngine;
    }

    /**
     * Start the llama.cpp subprocess with the configured model.
     * The process is started with GBNF grammar-constrained decoding.
     */
    public synchronized boolean start() {
        if (available) {
            return true;
        }

        String modelPath = config.getLlamaCpp().getModelPath();
        Path resolved = Path.of(modelPath);

        if (!Files.exists(resolved)) {
            log.warn("C7 LlamaCpp: Model not found at '{}'. SLM inference disabled. " +
                "Place a quantized GGUF model (1B-3B) at this path.", modelPath);
            this.available = false;
            return false;
        }

        try {
            // Build the llama.cpp command with grammar-constrained decoding
            String gbnfGrammar = grammarEngine.loadVetoOutputGrammar();
            this.grammarTempFile = Files.createTempFile("veto-grammar-", ".gbnf");
            this.grammarTempFile.toFile().deleteOnExit();
            Files.writeString(this.grammarTempFile, gbnfGrammar);

            ProcessBuilder pb = new ProcessBuilder(
                "llama-server",
                "--model", modelPath,
                "--ctx-size", String.valueOf(config.getLlamaCpp().getNCtx()),
                "--n-gpu-layers", String.valueOf(config.getLlamaCpp().getNGpuLayers()),
                "--temp", String.valueOf(config.getLlamaCpp().getTemperature()),
                "--grammar-file", this.grammarTempFile.toAbsolutePath().toString(),
                "--port", "0", // Random port
                "--embedding", "false",
                "--cont-batching"
            );

            pb.redirectErrorStream(true);
            llamaProcess = pb.start();
            processInput = new PrintWriter(new OutputStreamWriter(llamaProcess.getOutputStream()), true);
            processOutput = new BufferedReader(new InputStreamReader(llamaProcess.getInputStream()));

            available = true;
            log.info("C7 LlamaCpp: Started llama.cpp with model '{}' (ctx={}, temp={})",
                modelPath, config.getLlamaCpp().getNCtx(), config.getLlamaCpp().getTemperature());

            // Start health monitor
            startHealthMonitor();

            return true;

        } catch (IOException e) {
            log.error("C7 LlamaCpp: Failed to start llama.cpp. Is it installed?", e);
            this.available = false;
            return false;
        }
    }

    /**
     * Perform SLM inference with grammar-constrained output.
     * Returns the model's structured response.
     */
    public CompletableFuture<String> infer(String prompt, String grammarName) {
        if (!available) {
            return CompletableFuture.completedFuture(
                "{\"veto_decision\":\"pass\",\"data\":{\"note\":\"SLM unavailable, passed without analysis\"}}");
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                String request = String.format(
                    "{\"prompt\":\"%s\",\"grammar\":\"%s\",\"n_predict\":1024}",
                    escapeJson(prompt), grammarName != null ? grammarName : "veto-output"
                );

                processInput.println(request);
                processInput.flush();

                StringBuilder response = new StringBuilder();
                int openBraces = 0;
                boolean jsonStarted = false;
                String line;
                long deadline = System.currentTimeMillis() + 30_000; // 30s timeout

                while (System.currentTimeMillis() < deadline) {
                    line = processOutput.readLine();
                    if (line == null) break;
                    
                    response.append(line);
                    for (char c : line.toCharArray()) {
                        if (c == '{') {
                            openBraces++;
                            jsonStarted = true;
                        } else if (c == '}') {
                            openBraces--;
                        }
                    }
                    
                    if (jsonStarted && openBraces == 0) {
                        break; // Complete JSON object detected
                    }
                }

                return response.toString();

            } catch (IOException e) {
                log.error("C7 LlamaCpp: Inference failed", e);
                return "{\"veto_decision\":\"pass\",\"data\":{\"error\":\"" +
                    escapeJson(e.getMessage()) + "\"}}";
            }
        }, inferenceExecutor);
    }

    /**
     * Kill request for priority interruption.
     */
    public void killCurrentInference() {
        // Implementation would send SIGINT or use llama.cpp's interrupt API
        log.warn("C7 LlamaCpp: Kill current inference requested");
    }

    /**
     * Gracefully stop the llama.cpp subprocess.
     */
    public synchronized void stop() {
        available = false;
        if (llamaProcess != null && llamaProcess.isAlive()) {
            llamaProcess.destroy();
            try {
                llamaProcess.waitFor(5, TimeUnit.SECONDS);
                if (llamaProcess.isAlive()) {
                    llamaProcess.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                llamaProcess.destroyForcibly();
            }
        }
        log.info("C7 LlamaCpp: Stopped");
    }

    private void startHealthMonitor() {
        inferenceExecutor.submit(() -> {
            while (available) {
                if (llamaProcess != null && !llamaProcess.isAlive()) {
                    log.warn("C7 LlamaCpp: Process died unexpectedly");
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

    /**
     * Restart the llama.cpp subprocess with a different model.
     * Used by TrainingManager after a new model is trained and deployed.
     *
     * @param newModelPath path to the new GGUF model file
     * @return true if the restart succeeded
     */
    public synchronized boolean restartWithModel(String newModelPath) {
        log.info("C7 LlamaCpp: Restarting with new model '{}'", newModelPath);
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

    public boolean isAvailable() { return available; }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
