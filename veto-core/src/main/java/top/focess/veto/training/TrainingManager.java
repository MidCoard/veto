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
import java.util.regex.Pattern;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import top.focess.veto.security.HostPathInput;

/**
 * Orchestrates the model training lifecycle. Launches Python training scripts as subprocesses (same
 * pattern as LlamaCppBridge). Monitors progress, handles cancellation, and auto-deploys trained
 * models.
 *
 * <p>Supports structured JSON progress output from train.py (Feature 6.2) and the quality filter
 * gate (Feature 6.3).
 */
@Service
public class TrainingManager {

    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.training.TrainingManager");

    /** Pattern for structured JSON progress lines emitted by train.py. */
    private static final @NonNull Pattern STRUCTURED_PROGRESS_PATTERN =
            Pattern.compile("^\\{\"type\"\\s*:\\s*\"(\\w+)\".*}$");

    private final @NonNull TrainingConfiguration config;
    private final @NonNull ObjectMapper objectMapper;

    private final @NonNull TrainingProgress progress = new TrainingProgress();
    private final @NonNull AtomicBoolean running = new AtomicBoolean(false);

    private Process trainingProcess;
    private final @NonNull ExecutorService processExecutor =
            Executors.newSingleThreadExecutor(
                    r -> {
                        Thread t = new Thread(r, "veto-training-monitor");
                        t.setDaemon(true);
                        return t;
                    });

    /** Callback interface for model deployment (wired to LlamaCppBridge restart). */
    @FunctionalInterface
    public interface ModelDeployCallback {
        void deploy(@NonNull String modelPath);
    }

    private ModelDeployCallback deployCallback = null;

    public TrainingManager(
            @NonNull TrainingConfiguration config, @NonNull ObjectMapper objectMapper) {
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
    public void setDeployCallback(@NonNull ModelDeployCallback callback) {
        this.deployCallback = callback;
    }

    /**
     * Start the full training pipeline with default configuration.
     *
     * @return true if training was successfully started
     */
    public synchronized boolean startTraining() {
        return startTraining(TrainingRequest.defaults());
    }

    /**
     * Start the full training pipeline as a Python subprocess. Generates data → quality filter →
     * trains → converts → evaluates. Accepts optional {@link TrainingRequest} to override defaults.
     *
     * @return true if training was successfully started
     */
    public synchronized boolean startTraining(TrainingRequest request) {
        if (running.get()) {
            log.warn("Training already in progress");
            return false;
        }

        if (request == null) {
            request = TrainingRequest.defaults();
        }

        // Resolve effective parameters (request overrides config)
        String effectiveBaseModel =
                request.baseModel() != null ? request.baseModel() : config.getBaseModel();
        Integer requestedEpochs = request.epochs();
        Double requestedLearningRate = request.learningRate();
        Integer requestedBatchSize = request.batchSize();
        Integer requestedLoraRank = request.loraRank();
        Boolean requestedSkipQualityFilter = request.skipQualityFilter();
        int effectiveEpochs = requestedEpochs != null ? requestedEpochs : 3;
        double effectiveLr = requestedLearningRate != null ? requestedLearningRate : 2e-4;
        int effectiveBatchSize = requestedBatchSize != null ? requestedBatchSize : 4;
        int effectiveLoraRank = requestedLoraRank != null ? requestedLoraRank : 16;
        boolean effectiveSkipQualityFilter =
                requestedSkipQualityFilter != null ? requestedSkipQualityFilter : false;

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

        // Capture request-scoped values for the async lambda
        final String baseModel = effectiveBaseModel;
        final int epochs = effectiveEpochs;
        final double lr = effectiveLr;
        final int batchSize = effectiveBatchSize;
        final int loraRank = effectiveLoraRank;
        final boolean skipQualityFilter = effectiveSkipQualityFilter;
        final String requestDataPath = request.dataPath();

        processExecutor.submit(
                () -> {
                    try {
                        // Step 1: Generate training data
                        progress.updatePhase("preparing_data", 0.05, "Generating training data...");
                        if (!runPythonScript(pythonDir, "prepare_data.py --skip-quality-check")) {
                            progress.fail("Data preparation failed");
                            running.set(false);
                            return;
                        }

                        // Step 2: Quality filter (Feature 6.3)
                        if (config.isQualityFilterEnabled() && !skipQualityFilter) {
                            progress.updatePhase(
                                    "quality_filter",
                                    0.1,
                                    "Running quality filter on training data...");
                            Path dataPath = resolveTrainingDataPath(trainingDir, requestDataPath);
                            if (!runQualityFilter(pythonDir, dataPath)) {
                                progress.fail(
                                        "Quality filter failed — training data contains invalid records");
                                running.set(false);
                                return;
                            }
                        }

                        // Step 3: Train model
                        progress.updatePhase("training", 0.15, "Fine-tuning model (QLoRA)...");
                        Path dataPath = resolveTrainingDataPath(trainingDir, requestDataPath);
                        Path outputDir =
                                config.getModelOutputDir().isEmpty()
                                        ? trainingDir.resolve("models").resolve("fine-tuned")
                                        : Path.of(config.getModelOutputDir());
                        outputDir = outputDir.toAbsolutePath();
                        Files.createDirectories(outputDir);

                        String trainArgs =
                                String.format(
                                        "train.py --base-model %s --data-path %s --output-dir %s "
                                                + "--epochs %d --lr %s --batch-size %d --lora-r %d"
                                                + " --structured-output",
                                        baseModel,
                                        dataPath.toAbsolutePath(),
                                        outputDir,
                                        epochs,
                                        lr,
                                        batchSize,
                                        loraRank);
                        if (!runPythonScript(pythonDir, trainArgs)) {
                            progress.fail("Training failed");
                            running.set(false);
                            return;
                        }

                        // Step 4: Convert to GGUF
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

                        // Step 5: Evaluate
                        progress.updatePhase("evaluating", 0.9, "Evaluating trained model...");
                        Path evalDataPath =
                                trainingDir.resolve("data").resolve("veto_eval_data.jsonl");
                        Path ggufModelPath = resolveGgufModelPath(outputDir, trainingDir);

                        if (Files.exists(evalDataPath) && Files.exists(ggufModelPath)) {
                            Path evalReportPath =
                                    outputDir.resolve("eval_report_java.json").toAbsolutePath();
                            String evalArgs =
                                    String.format(
                                            "evaluate.py --model %s --data %s --output %s --json-output",
                                            ggufModelPath.toAbsolutePath(),
                                            evalDataPath.toAbsolutePath(),
                                            evalReportPath);
                            runPythonScript(pythonDir, evalArgs);

                            // Parse evaluation report into TrainingProgress.EvaluationReport
                            parseEvaluationReport(evalReportPath);
                        }

                        // Determine final model path
                        Path finalGguf =
                                Path.of(config.getModelOutputDir())
                                        .resolve(config.getDefaultGgufName())
                                        .toAbsolutePath();
                        if (!Files.exists(finalGguf)) {
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
                        String message = e.getMessage();
                        progress.fail(
                                message == null || message.isBlank()
                                        ? e.getClass().getSimpleName()
                                        : message);
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
     * Run the quality filter on existing training data without starting a training run.
     *
     * @return the quality filter report as a Map, or null on failure
     */
    public Map<String, Object> runStandaloneQualityCheck() {
        Path trainingDir = Path.of(config.getTrainingDir()).toAbsolutePath();
        Path pythonDir = trainingDir.resolve("python");
        Path dataPath = trainingDir.resolve("data").resolve("veto_training_data.jsonl");

        if (!Files.exists(dataPath)) {
            log.error("Training data not found: {}", dataPath);
            return null;
        }

        Path reportPath = trainingDir.resolve("data").resolve("quality_report_standalone.json");

        String filterArgs =
                String.format(
                        "quality_filter.py --data %s --report %s --fail-on-invalid",
                        dataPath.toAbsolutePath(), reportPath.toAbsolutePath());

        String python = resolvePythonPath();
        String[] cmd =
                new String[] {
                    python,
                    "quality_filter.py",
                    "--data",
                    dataPath.toAbsolutePath().toString(),
                    "--report",
                    reportPath.toAbsolutePath().toString(),
                    "--fail-on-invalid"
                };

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(pythonDir.toFile());
        pb.redirectErrorStream(true);

        try {
            Process process = pb.start();
            try (BufferedReader reader =
                    new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                reader.transferTo(java.io.Writer.nullWriter());
            }
            boolean finished = process.waitFor(5, TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
                return null;
            }
            if (process.exitValue() != 0) {
                log.warn("Quality filter reported invalid records");
            }

            if (Files.exists(reportPath)) {
                @SuppressWarnings("unchecked")
                Map<String, Object> report = objectMapper.readValue(reportPath.toFile(), Map.class);
                return report;
            }
        } catch (Exception e) {
            log.error("Quality filter failed", e);
        }
        return null;
    }

    /**
     * Deploy a trained GGUF model to the path expected by LlamaCppBridge. Optionally triggers the
     * bridge restart callback.
     *
     * @param modelPath path to the GGUF model file
     * @return true if deployment succeeded
     */
    public boolean deployModel(@NonNull String modelPath) {
        Path source;
        try {
            // The source is subsequently confined to configured roots and resolved to a real path.
            //noinspection tainting
            source = HostPathInput.normalized(modelPath, "modelPath");
        } catch (IllegalArgumentException e) {
            log.error("Refusing to deploy an invalid model path", e);
            return false;
        }
        Path trainingRoot = Path.of(config.getTrainingDir()).toAbsolutePath().normalize();
        Path modelRoot = Path.of(config.getModelOutputDir()).toAbsolutePath().normalize();
        if (!source.startsWith(trainingRoot) && !source.startsWith(modelRoot)) {
            log.error("Refusing to deploy a model outside the configured training directories");
            return false;
        }
        Path fileName = source.getFileName();
        // The preceding lexical root check confines this first existence/type probe.
        //noinspection tainting
        if (!Files.isRegularFile(source)
                || fileName == null
                || !fileName.toString().toLowerCase(java.util.Locale.ROOT).endsWith(".gguf")) {
            log.error("Model path is not a regular GGUF file: {}", modelPath);
            return false;
        }

        try {
            // Resolve symlinks, then repeat the configured-root check on the real path.
            //noinspection tainting
            source = source.toRealPath();
            Path realTrainingRoot = existingRealPath(trainingRoot);
            Path realModelRoot = existingRealPath(modelRoot);
            if (!source.startsWith(realTrainingRoot) && !source.startsWith(realModelRoot)) {
                log.error("Refusing to deploy a model through a path outside the configured roots");
                return false;
            }
        } catch (IOException e) {
            log.error("Could not resolve model path", e);
            return false;
        }

        Path target = modelRoot.resolve(config.getDefaultGgufName());

        try {
            Files.createDirectories(modelRoot);
            // source has passed lexical and real-path root checks; target is configuration-derived.
            //noinspection tainting
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

    private static @NonNull Path existingRealPath(@NonNull Path path) throws IOException {
        return Files.exists(path) ? path.toRealPath() : path;
    }

    // ── Internal helpers ──

    /** Run the quality filter Python script on the given data path. */
    private boolean runQualityFilter(@NonNull Path pythonDir, @NonNull Path dataPath) {
        String python = resolvePythonPath();
        Path dataDirectory = dataPath.getParent();
        if (dataDirectory == null) {
            log.error("Training data path has no parent directory: {}", dataPath);
            return false;
        }
        Path reportPath = dataDirectory.resolve("quality_report.json");

        String[] cmd =
                new String[] {
                    python,
                    "quality_filter.py",
                    "--data",
                    dataPath.toAbsolutePath().toString(),
                    "--report",
                    reportPath.toAbsolutePath().toString(),
                    "--fail-on-invalid"
                };

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(pythonDir.toFile());
        pb.redirectErrorStream(true);

        try {
            Process process = pb.start();
            try (BufferedReader reader =
                    new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.info("[QUALITY-FILTER] {}", line);
                }
            }
            boolean finished = process.waitFor(5, TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (Exception e) {
            log.error("Quality filter execution failed", e);
            return false;
        }
    }

    /**
     * Parse the evaluation report JSON (produced by evaluate.py --json-output) into a
     * TrainingProgress.EvaluationReport and attach it to the progress.
     */
    private void parseEvaluationReport(@NonNull Path reportPath) {
        if (!Files.exists(reportPath)) {
            log.warn("Evaluation report not found: {}", reportPath);
            return;
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> report = objectMapper.readValue(reportPath.toFile(), Map.class);

            Map<String, Object> gbnf = requiredMap(report, "gbnfCompliance");
            Map<String, Object> decision = requiredMap(report, "decisionAccuracy");
            Map<String, Object> redaction = requiredMap(report, "redactionAccuracy");
            Map<String, Object> structural = requiredMap(report, "structuralValidation");

            TrainingProgress.EvaluationReport evalReport =
                    new TrainingProgress.EvaluationReport(
                            (String) report.get("modelPath"),
                            (String) report.get("datasetPath"),
                            (String) report.get("timestamp"),
                            requiredNumber(report, "totalSamples").intValue(),
                            requiredNumber(report, "elapsedSeconds").doubleValue(),
                            new TrainingProgress.EvaluationReport.GbnfCompliance(
                                    requiredNumber(gbnf, "validJsonCount").intValue(),
                                    requiredNumber(gbnf, "validJsonRate").doubleValue()),
                            new TrainingProgress.EvaluationReport.DecisionAccuracy(
                                    requiredNumber(decision, "correct").intValue(),
                                    requiredNumber(decision, "total").intValue(),
                                    requiredNumber(decision, "accuracy").doubleValue()),
                            new TrainingProgress.EvaluationReport.RedactionAccuracy(
                                    requiredNumber(redaction, "truePositives").intValue(),
                                    requiredNumber(redaction, "falsePositives").intValue(),
                                    requiredNumber(redaction, "falseNegatives").intValue(),
                                    requiredNumber(redaction, "precision").doubleValue(),
                                    requiredNumber(redaction, "recall").doubleValue(),
                                    requiredNumber(redaction, "f1").doubleValue()),
                            new TrainingProgress.EvaluationReport.StructuralValidation(
                                    requiredNumber(structural, "correct").intValue(),
                                    requiredNumber(structural, "total").intValue(),
                                    requiredNumber(structural, "accuracy").doubleValue()));

            progress.setEvaluation(evalReport);
            log.info(
                    "Evaluation report parsed: GBNF compliance={}, decision accuracy={}",
                    String.format(
                            java.util.Locale.ROOT,
                            "%.1f%%",
                            evalReport.gbnfCompliance().validJsonRate() * 100.0),
                    String.format(
                            java.util.Locale.ROOT,
                            "%.1f%%",
                            evalReport.decisionAccuracy().accuracy() * 100.0));
        } catch (Exception e) {
            log.warn(
                    "Failed to parse evaluation report: {}",
                    java.util.Objects.toString(e.getMessage()));
        }
    }

    private static @NonNull Number requiredNumber(
            @NonNull Map<String, Object> values, @NonNull String key) {
        Object value = values.get(key);
        if (value instanceof Number number) {
            return number;
        }
        throw new IllegalArgumentException(
                "Evaluation report field '" + key + "' must be a number");
    }

    private static @NonNull Map<String, Object> requiredMap(
            @NonNull Map<String, Object> values, @NonNull String key) {
        Object value = values.get(key);
        if (!(value instanceof Map<?, ?> raw)) {
            throw new IllegalArgumentException(
                    "Evaluation report field '" + key + "' must be an object");
        }
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String entryKey)) {
                throw new IllegalArgumentException(
                        "Evaluation report object '" + key + "' has a non-string key");
            }
            Object entryValue = entry.getValue();
            if (entryValue != null) {
                result.put(entryKey, entryValue);
            }
        }
        return result;
    }

    private @NonNull Path resolveTrainingDataPath(
            @NonNull Path trainingDir, String requestDataPath) {
        if (requestDataPath != null && !requestDataPath.isEmpty()) {
            Path custom = Path.of(requestDataPath);
            if (Files.exists(custom)) {
                return custom;
            }
            log.warn("Custom data path does not exist: {}, using default", requestDataPath);
        }
        return trainingDir.resolve("data").resolve("veto_training_data.jsonl");
    }

    private @NonNull Path resolveGgufModelPath(@NonNull Path outputDir, @NonNull Path trainingDir) {
        // Try the output dir first
        Path candidate = outputDir.resolve("veto-slm-q4_k_m.gguf");
        if (Files.exists(candidate)) {
            return candidate;
        }
        // Fallback: training/models/
        return trainingDir.resolve("models").resolve("veto-slm-q4_k_m.gguf");
    }

    /**
     * Run a Python script located in the training/python directory.
     *
     * @param workingDir the python/ directory
     * @param command the command to run (e.g. "prepare_data.py" or "train.py --epochs 3")
     * @return true if the script exited with code 0
     */
    private boolean runPythonScript(@NonNull Path workingDir, @NonNull String command) {
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

                    // Try parsing as structured JSON progress
                    parseProgressLine(line);
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

    /**
     * Parse a structured JSON progress line from train.py stdout. Lines like: {@code
     * {"type":"progress","epoch":1,"step":50,"loss":0.45}} {@code
     * {"type":"phase","phase":"training","message":"Starting QLoRA fine-tuning..."}}
     */
    private void parseProgressLine(String line) {
        if (line == null || line.isBlank()) {
            return;
        }
        String trimmed = line.trim();
        if (!STRUCTURED_PROGRESS_PATTERN.matcher(trimmed).matches()) {
            // Not structured JSON — fall back to string-matching for legacy output
            if (trimmed.contains("Training complete!")) {
                progress.updatePhase(
                        "converting", 0.7, "Training complete, starting conversion...");
            } else if (trimmed.contains("Conversion complete!")) {
                progress.updatePhase("evaluating", 0.85, "Conversion complete, evaluating...");
            }
            return;
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = objectMapper.readValue(trimmed, Map.class);
            String type = (String) parsed.get("type");

            if (type == null) {
                log.debug("Structured training progress line omitted type: {}", trimmed);
                return;
            }

            switch (type) {
                case "phase" -> {
                    String phase = (String) parsed.get("phase");
                    String message = (String) parsed.get("message");
                    if (phase != null && message != null) {
                        double progressVal =
                                switch (phase) {
                                    case "quality_filter" -> 0.1;
                                    case "training" -> 0.15;
                                    case "converting" -> 0.8;
                                    case "evaluating" -> 0.9;
                                    default -> progress.getProgress();
                                };
                        progress.updatePhase(phase, progressVal, message);
                    }
                }
                case "progress" -> {
                    // Fine-grained step progress during training
                    Object stepObj = parsed.get("step");
                    Object maxStepsObj = parsed.get("maxSteps");
                    if (stepObj != null && maxStepsObj != null) {
                        int step = ((Number) stepObj).intValue();
                        int maxSteps = ((Number) maxStepsObj).intValue();
                        if (maxSteps > 0) {
                            // Map training steps to the 0.15–0.75 progress range
                            double trainingProgress = 0.15 + 0.60 * ((double) step / maxSteps);
                            progress.updatePhase(
                                    "training",
                                    trainingProgress,
                                    String.format(
                                            "Training step %d/%d (loss=%s)",
                                            step, maxSteps, parsed.getOrDefault("loss", "N/A")));
                        }
                    }
                }
                case "epoch_start" ->
                        log.info(
                                "Epoch {} started",
                                java.util.Objects.toString(parsed.get("epoch")));
                case "epoch_end" ->
                        log.info("Epoch {} ended", java.util.Objects.toString(parsed.get("epoch")));
                case "phase_complete" ->
                        log.info(
                                "Phase {} complete",
                                java.util.Objects.toString(parsed.get("phase")));
                case "error" ->
                        log.error(
                                "Training error: {}",
                                java.util.Objects.toString(parsed.get("message")));
                default -> log.debug("Unknown progress type: {}", type);
            }
        } catch (Exception e) {
            // Not valid JSON or unexpected structure — ignore silently
        }
    }

    /** Resolve the Python interpreter path, preferring the venv if it exists. */
    private @NonNull String resolvePythonPath() {
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
        return config.getPythonPath();
    }

    private void killProcess() {
        Process process = trainingProcess;
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
    }

    // ── Public accessors ──

    public @NonNull TrainingProgress getProgress() {
        return progress;
    }

    public boolean isRunning() {
        return running.get();
    }
}
