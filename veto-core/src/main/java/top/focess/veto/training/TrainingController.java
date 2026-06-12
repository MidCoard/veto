package top.focess.veto.training;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST API for managing the Veto SLM model training pipeline.
 *
 * <p>Endpoints: POST /api/v1/training/start - Start a training run POST /api/v1/training/cancel -
 * Cancel current training GET /api/v1/training/progress - Get training progress POST
 * /api/v1/training/deploy - Deploy a trained model to the gateway GET /api/v1/training/status - Get
 * overall training system status
 */
@RestController
@RequestMapping("/api/v1/training")
public class TrainingController {

    private static final Logger log = LoggerFactory.getLogger(TrainingController.class);

    private final TrainingManager trainingManager;
    private final TrainingConfiguration config;

    public TrainingController(TrainingManager trainingManager, TrainingConfiguration config) {
        this.trainingManager = trainingManager;
        this.config = config;
    }

    /**
     * Start the full training pipeline. Generates data -> fine-tunes -> converts to GGUF ->
     * evaluates -> auto-deploys.
     */
    @PostMapping("/start")
    public ResponseEntity<Map<String, Object>> startTraining() {
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

        boolean started = trainingManager.startTraining();
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
    public ResponseEntity<Map<String, Object>> cancelTraining() {
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
    public ResponseEntity<Map<String, Object>> getProgress() {
        TrainingProgress p = trainingManager.getProgress();
        return ResponseEntity.ok(
                Map.of(
                        "status", p.getStatus().name(),
                        "progress", p.getProgress(),
                        "phase", p.getCurrentPhase(),
                        "message", p.getMessage(),
                        "startedAt", p.getStartedAt(),
                        "completedAt", p.getCompletedAt(),
                        "trainedModelPath", p.getTrainedModelPath(),
                        "error", p.getErrorMessage(),
                        "evaluation", p.getEvaluation()));
    }

    /**
     * Deploy a trained model to the path expected by LlamaCppBridge. Optionally restarts the bridge
     * with the new model.
     *
     * <p>Request body: { "modelPath": "./training/models/veto-slm-q4_k_m.gguf" }
     */
    @PostMapping("/deploy")
    public ResponseEntity<Map<String, Object>> deployModel(
            @RequestBody Map<String, String> request) {
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
                            "modelPath",
                            modelPath,
                            "targetPath",
                            config.getModelOutputDir() + "/" + config.getDefaultGgufName()));
        } else {
            return ResponseEntity.status(500)
                    .body(
                            Map.of(
                                    "success",
                                    false,
                                    "message",
                                    "Failed to deploy model. Check that the file exists.",
                                    "modelPath",
                                    modelPath));
        }
    }

    /** Get overall training system status (configuration info). */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        return ResponseEntity.ok(
                Map.of(
                        "running", trainingManager.isRunning(),
                        "baseModel", config.getBaseModel(),
                        "trainingDir", config.getTrainingDir(),
                        "modelOutputDir", config.getModelOutputDir(),
                        "defaultGgufName", config.getDefaultGgufName(),
                        "autoDeployOnCompletion", config.isAutoDeployOnCompletion(),
                        "maxTrainingHours", config.getMaxTrainingHours(),
                        "progress", trainingManager.getProgress()));
    }
}
