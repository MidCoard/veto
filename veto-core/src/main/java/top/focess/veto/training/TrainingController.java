package top.focess.veto.training;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST API for managing the Veto SLM model training pipeline.
 *
 * <p>Endpoints:
 *
 * <ul>
 *   <li>POST /api/v1/training/start - Start a training run (accepts optional {@link
 *       TrainingRequest} body)
 *   <li>POST /api/v1/training/cancel - Cancel current training
 *   <li>GET /api/v1/training/progress - Get training progress
 *   <li>POST /api/v1/training/deploy - Deploy a trained model to the gateway
 *   <li>GET /api/v1/training/status - Get overall training system status
 *   <li>POST /api/v1/training/quality-check - Run quality filter on training data (Feature 6.3)
 *   <li>GET /api/v1/training/evaluation - Get the latest evaluation report
 */
@RestController
@RequestMapping("/api/v1/training")
public class TrainingController {

    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.training.TrainingController");

    private final @NonNull TrainingManager trainingManager;
    private final @NonNull TrainingConfiguration config;

    public TrainingController(
            @NonNull TrainingManager trainingManager, @NonNull TrainingConfiguration config) {
        this.trainingManager = trainingManager;
        this.config = config;
    }

    /**
     * Start the full training pipeline. Generates data → quality filter → fine-tunes → converts to
     * GGUF → evaluates → auto-deploys. Accepts optional JSON body with custom training parameters.
     *
     * <p>Request body (all fields optional): {@code { "baseModel": "Qwen/Qwen2.5-0.5B-Instruct",
     * "epochs": 1, "learningRate": 2e-4, "batchSize": 2, "loraRank": 16, "dataPath":
     * "/path/to/custom_data.jsonl", "skipQualityFilter": false }}
     */
    @PostMapping("/start")
    public @NonNull ResponseEntity<Map<String, Object>> startTraining(
            @RequestBody(required = false) TrainingRequest request) {
        if (trainingManager.isRunning()) {
            return ResponseEntity.status(409)
                    .body(
                            Map.of(
                                    "success",
                                    false,
                                    "message",
                                    "Training is already in progress",
                                    "progress",
                                    trainingManager.getProgress()));
        }

        boolean started = trainingManager.startTraining(request);
        if (started) {
            log.info("Training pipeline started");
            return ResponseEntity.ok(
                    Map.of(
                            "success",
                            true,
                            "message",
                            "Training pipeline started",
                            "baseModel",
                            config.getBaseModel(),
                            "progress",
                            trainingManager.getProgress()));
        } else {
            return ResponseEntity.status(500)
                    .body(
                            Map.of(
                                    "success",
                                    false,
                                    "message",
                                    "Failed to start training pipeline. Check configuration.",
                                    "error",
                                    trainingManager.getProgress().getErrorMessage()));
        }
    }

    /** Cancel the current training run. */
    @PostMapping("/cancel")
    public @NonNull ResponseEntity<Map<String, Object>> cancelTraining() {
        if (!trainingManager.isRunning()) {
            return ResponseEntity.ok(
                    Map.of(
                            "success",
                            true,
                            "message",
                            "No training in progress",
                            "progress",
                            trainingManager.getProgress()));
        }

        trainingManager.cancelTraining();
        log.warn("Training cancelled by user request");
        return ResponseEntity.ok(
                Map.of(
                        "success",
                        true,
                        "message",
                        "Training cancelled",
                        "progress",
                        trainingManager.getProgress()));
    }

    /** Get current training progress. */
    @GetMapping("/progress")
    public @NonNull ResponseEntity<Map<String, Object>> getProgress() {
        TrainingProgress p = trainingManager.getProgress();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", p.getStatus().name());
        body.put("progress", p.getProgress());
        body.put("phase", p.getCurrentPhase());
        body.put("message", p.getMessage());
        Instant startedAt = p.getStartedAt();
        if (startedAt != null) body.put("startedAt", startedAt);
        Instant completedAt = p.getCompletedAt();
        if (completedAt != null) body.put("completedAt", completedAt);
        body.put("trainedModelPath", p.getTrainedModelPath());
        body.put("error", p.getErrorMessage());
        TrainingProgress.EvaluationReport evaluation = p.getEvaluation();
        if (evaluation != null) body.put("evaluation", evaluation);
        return ResponseEntity.ok(body);
    }

    /**
     * Deploy a trained model to the path expected by LlamaCppBridge. Optionally restarts the bridge
     * with the new model.
     *
     * <p>Request body: { "modelPath": "./training/models/veto-slm-q4_k_m.gguf" }
     */
    @PostMapping(
            value = "/deploy",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public @NonNull ResponseEntity<Map<String, Object>> deployModel(
            @RequestBody @NonNull Map<String, String> request) {
        String modelPath = request.get("modelPath");
        if (modelPath == null || modelPath.isEmpty()) {
            // Default to the most recent trained model
            modelPath = config.getModelOutputDir() + "/" + config.getDefaultGgufName();
        }

        boolean deployed = trainingManager.deployModel(modelPath);
        if (deployed) {
            log.info("Model deployed: {}", modelPath);
            return ResponseEntity.ok(
                    Map.of(
                            "success",
                            true,
                            "message",
                            "Model deployed successfully",
                            "targetPath",
                            config.getModelOutputDir() + "/" + config.getDefaultGgufName()));
        } else {
            return ResponseEntity.status(500)
                    .body(
                            Map.of(
                                    "success",
                                    false,
                                    "message",
                                    "Failed to deploy model. Check that the file exists."));
        }
    }

    /** Get overall training system status (configuration info). */
    @GetMapping("/status")
    public @NonNull ResponseEntity<Map<String, Object>> getStatus() {
        return ResponseEntity.ok(
                Map.of(
                        "running", trainingManager.isRunning(),
                        "baseModel", config.getBaseModel(),
                        "trainingDir", config.getTrainingDir(),
                        "modelOutputDir", config.getModelOutputDir(),
                        "defaultGgufName", config.getDefaultGgufName(),
                        "autoDeployOnCompletion", config.isAutoDeployOnCompletion(),
                        "qualityFilterEnabled", config.isQualityFilterEnabled(),
                        "maxTrainingHours", config.getMaxTrainingHours(),
                        "progress", trainingManager.getProgress()));
    }

    /**
     * Run the quality filter (Feature 6.3) on existing training data without starting a training
     * run. Returns the filter report.
     */
    @PostMapping("/quality-check")
    public @NonNull ResponseEntity<Map<String, Object>> runQualityCheck() {
        Map<String, Object> report = trainingManager.runStandaloneQualityCheck();
        if (report != null) {
            return ResponseEntity.ok(
                    Map.of(
                            "success",
                            true,
                            "message",
                            "Quality check completed",
                            "report",
                            report));
        } else {
            return ResponseEntity.status(500)
                    .body(
                            Map.of(
                                    "success",
                                    false,
                                    "message",
                                    "Quality check failed. Training data may not exist yet."));
        }
    }

    /** Get the latest evaluation report from the most recent training run. */
    @GetMapping("/evaluation")
    public @NonNull ResponseEntity<Map<String, Object>> getEvaluation() {
        TrainingProgress.EvaluationReport eval = trainingManager.getProgress().getEvaluation();
        if (eval != null) {
            return ResponseEntity.ok(Map.of("success", true, "evaluation", eval));
        } else {
            return ResponseEntity.ok(
                    Map.of(
                            "success",
                            false,
                            "message",
                            "No evaluation report available. Run training first."));
        }
    }
}
