package top.focess.veto.training;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Orchestrates the model training lifecycle. Launches Python training scripts as subprocesses (same
 * pattern as LlamaCppBridge). Monitors progress, handles cancellation, and auto-deploys trained
 * models.
 */
@Service
public class TrainingManager {

    private static final Logger log = LoggerFactory.getLogger(TrainingManager.class);

    private final TrainingConfiguration config;
    private final ObjectMapper objectMapper;

    private final TrainingProgress progress = new TrainingProgress();
    private final AtomicBoolean running = new AtomicBoolean(false);

    private Process trainingProcess;
    private final ExecutorService processExecutor =
            Executors.newSingleThreadExecutor(
                    r -> {
                        Thread t = new Thread(r, "veto-training-monitor");
                        t.setDaemon(true);
                        return t;
                    });

    /** Callback interface for model deployment (wired to LlamaCppBridge restart). */
    @FunctionalInterface
    public interface ModelDeployCallback {
        void deploy(String modelPath);
    }

    private ModelDeployCallback deployCallback = null;

    public TrainingManager(TrainingConfiguration config, ObjectMapper objectMapper) {
        this.config = config;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        log.info(
                "TrainingManager initialized. Base model: {}, Training dir: {}",
                config.getBaseModel(),
                config.getTrainingDir());
    }

    @PreDestroy
    public void shutdown() {
        if (running.get()) {
            cancelTraining();
        }
        processExecutor.shutdownNow();
    }

    /**
     * Register a callback that fires when deployment is needed. Typically wired to {@code
     * LlamaCppBridge::restartWithModel}.
     */
    public void setDeployCallback(ModelDeployCallback callback) {
        this.deployCallback = callback;
    }

    /**
     * Start the full training pipeline as a Python subprocess. Generates data -> trains -> converts
     * -> evaluates.
     *
     * @return true if training was successfully started
     */
    public synchronized boolean startTraining() {
        if (running.get()) {
            log.warn("Training already in progress");
            return false;
        }

        // Validate training directory
        Path trainingDir = Path.of(config.getTrainingDir()).toAbsolutePath();
        if (!Files.exists(trainingDir)) {
            log.error("Training directory not found: {}", trainingDir);
            progress.fail("Training directory not found: " + trainingDir);
            return false;
        }

        Path pythonDir = trainingDir.resolve("python");
        Path prepareScript = pythonDir.resolve("prepare_data.py");
        Path trainScript = pythonDir.resolve("train.py");
        Path convertScript = pythonDir.resolve("convert_to_gguf.py");

        if (!Files.exists(prepareScript)) {
            log.error("prepare_data.py not found at {}", prepareScript);
            progress.fail("prepare_data.py not found");
            return false;
        }
        if (!Files.exists(trainScript)) {
            log.error("train.py not found at {}", trainScript);
            progress.fail("train.py not found");
            return false;
        }

        progress.start();
        running.set(true);

        processExecutor.submit(
                () -> {
                    try {
                        // Step 1: Generate training data
                        progress.updatePhase("preparing_data", 0.1, "Generating training data...");
                        if (!runPythonScript(pythonDir, "prepare_data.py")) {
                            progress.fail("Data preparation failed");
                            running.set(false);
                            return;
                        }

                        // Step 2: Train model
                        progress.updatePhase("training", 0.3, "Fine-tuning model (QLoRA)...");
                        Path dataPath =
                                trainingDir.resolve("data").resolve("veto_training_data.jsonl");
                        Path outputDir =
                                config.getModelOutputDir().isEmpty()
                                        ? trainingDir.resolve("models").resolve("fine-tuned")
                                        : Path.of(config.getModelOutputDir());
                        outputDir = outputDir.toAbsolutePath();
                        Files.createDirectories(outputDir);

                        String trainArgs =
                                String.format(
                                        "train.py --base-model %s --data-path %s --output-dir %s --epochs 3",
                                        config.getBaseModel(),
                                        dataPath.toAbsolutePath(),
                                        outputDir);
                        if (!runPythonScript(pythonDir, trainArgs)) {
                            progress.fail("Training failed");
                            running.set(false);
                            return;
                        }

                        // Step 3: Convert to GGUF
                        progress.updatePhase("converting", 0.8, "Converting to GGUF Q4_K_M...");
                        Path mergedDir = outputDir.resolve("merged");
                        String convertArgs =
                                String.format(
                                        "convert_to_gguf.py --model-dir %s --quantize-type q4_k_m --model-name veto-slm",
                                        mergedDir.toAbsolutePath());
                        if (!runPythonScript(pythonDir, convertArgs)) {
                            progress.fail("GGUF conversion failed");
                            running.set(false);
                            return;
                        }

                        // Step 4: Evaluate
                        progress.updatePhase("evaluating", 0.9, "Evaluating trained model...");
                        Path evalDataPath =
                                trainingDir.resolve("data").resolve("veto_eval_data.jsonl");
                        Path ggufModelPath =
                                Path.of(config.getModelOutputDir())
                                        .resolve("veto-slm-q4_k_m.gguf")
                                        .toAbsolutePath();
                        if (!Files.exists(ggufModelPath)) {
                            // Fallback: look in training/models/
                            ggufModelPath =
                                    trainingDir.resolve("models").resolve("veto-slm-q4_k_m.gguf");
                        }

                        if (Files.exists(evalDataPath) && Files.exists(ggufModelPath)) {
                            String evalArgs =
                                    String.format(
                                            "evaluate.py --model %s --data %s --output %s",
                                            ggufModelPath.toAbsolutePath(),
                                            evalDataPath.toAbsolutePath(),
                                            trainingDir
                                                    .resolve("models")
                                                    .resolve("eval_report.json")
                                                    .toAbsolutePath());
                            runPythonScript(pythonDir, evalArgs);
                        }

                        // Determine final model path
                        Path finalGguf =
                                Path.of(config.getModelOutputDir())
                                        .resolve(config.getDefaultGgufName())
                                        .toAbsolutePath();
                        if (!Files.exists(finalGguf)) {
                            // Try q4_k_m variant
                            Path q4Gguf =
                                    Path.of(config.getModelOutputDir())
                                            .resolve("veto-slm-q4_k_m.gguf")
                                            .toAbsolutePath();
                            if (Files.exists(q4Gguf)) {
                                Files.copy(q4Gguf, finalGguf, StandardCopyOption.REPLACE_EXISTING);
                            }
                        }

                        progress.complete(finalGguf.toString());

                        // Auto-deploy if configured
                        if (config.isAutoDeployOnCompletion() && Files.exists(finalGguf)) {
                            deployModel(finalGguf.toString());
                        }

                    } catch (Exception e) {
                        log.error("Training pipeline failed", e);
                        progress.fail(e.getMessage());
                    } finally {
                        running.set(false);
                    }
                });

        return true;
    }

    /** Cancel the current training run by killing the Python subprocess. */
    public synchronized void cancelTraining() {
        if (!running.get()) {
            return;
        }
        log.warn("Cancelling training...");
        killProcess();
        progress.cancel();
        running.set(false);
    }

    /**
     * Deploy a trained GGUF model to the path expected by LlamaCppBridge. Optionally triggers the
     * bridge restart callback.
     *
     * @param modelPath path to the GGUF model file
     * @return true if deployment succeeded
     */
    public boolean deployModel(String modelPath) {
        Path source = Path.of(modelPath);
        if (!Files.exists(source)) {
            log.error("Model file not found: {}", modelPath);
            return false;
        }

        Path targetDir = Path.of(config.getModelOutputDir()).toAbsolutePath();
        Path target = targetDir.resolve(config.getDefaultGgufName());

        try {
            Files.createDirectories(targetDir);
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            log.info("Model deployed: {} -> {}", source, target);

            if (config.isRestartBridgeOnDeploy() && deployCallback != null) {
                deployCallback.deploy(target.toString());
                log.info("Bridge restart callback invoked for model: {}", target);
            }

            return true;
        } catch (IOException e) {
            log.error("Failed to deploy model", e);
            return false;
        }
    }

    /**
     * Run a Python script located in the training/python directory.
     *
     * @param workingDir the python/ directory
     * @param command the command to run (e.g. "prepare_data.py" or "train.py --epochs 3")
     * @return true if the script exited with code 0
     */
    private boolean runPythonScript(Path workingDir, String command) {
        // Resolve Python interpreter (prefer venv)
        String python = resolvePythonPath();

        // Build the command
        String[] cmd;
        if (command.contains(" ")) {
            // Split script name and args
            String[] parts = command.split(" ", 2);
            cmd = new String[] {python, parts[0], parts[1]};
        } else {
            cmd = new String[] {python, command};
        }

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(workingDir.toFile());
        pb.redirectErrorStream(true);

        // Set environment variables for Python
        Map<String, String> env = pb.environment();
        env.put("PYTHONUNBUFFERED", "1");
        if (!config.getBaseModel().isEmpty()) {
            env.put("VETO_BASE_MODEL", config.getBaseModel());
        }

        log.info("Running: {} {} (cwd={})", cmd[0], cmd[1], workingDir);

        try {
            Process process = pb.start();
            this.trainingProcess = process;

            // Read output line by line and log it
            try (BufferedReader reader =
                    new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.info("[TRAINING] {}", line);
                    // Parse progress indicators from output
                    if (line.contains("Training complete!")) {
                        progress.updatePhase(
                                "converting", 0.7, "Training complete, starting conversion...");
                    } else if (line.contains("Conversion complete!")) {
                        progress.updatePhase(
                                "evaluating", 0.85, "Conversion complete, evaluating...");
                    }
                }
            }

            boolean finished = process.waitFor(config.getMaxTrainingHours(), TimeUnit.HOURS);
            if (!finished || process.isAlive()) {
                log.warn("Training timed out after {} hours", config.getMaxTrainingHours());
                process.destroyForcibly();
                return false;
            }

            int actualExit = process.exitValue();
            if (actualExit != 0) {
                log.error("Python script exited with code {}", actualExit);
                return false;
            }

            return true;

        } catch (IOException e) {
            log.error("Failed to start Python subprocess", e);
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Training interrupted");
            return false;
        } finally {
            this.trainingProcess = null;
        }
    }

    /** Resolve the Python interpreter path, preferring the venv if it exists. */
    private String resolvePythonPath() {
        // If a venv is configured and exists, use its Python
        if (!config.getVenvPath().isEmpty()) {
            Path venvPython;
            String os = System.getProperty("os.name").toLowerCase();
            if (os.contains("win")) {
                venvPython = Path.of(config.getVenvPath(), "Scripts", "python.exe");
            } else {
                venvPython = Path.of(config.getVenvPath(), "bin", "python3");
            }
            if (Files.exists(venvPython)) {
                return venvPython.toAbsolutePath().toString();
            }
        }

        // Fall back to configured python path
        return config.getPythonPath();
    }

    private void killProcess() {
        if (trainingProcess != null && trainingProcess.isAlive()) {
            trainingProcess.destroy();
            try {
                trainingProcess.waitFor(5, TimeUnit.SECONDS);
                if (trainingProcess.isAlive()) {
                    trainingProcess.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                trainingProcess.destroyForcibly();
            }
        }
    }

    // ── Public accessors ──

    public TrainingProgress getProgress() {
        return progress;
    }

    public boolean isRunning() {
        return running.get();
    }
}
