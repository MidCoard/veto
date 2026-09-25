package top.focess.veto.training;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import top.focess.veto.controller.RequestAuthorization;
import top.focess.veto.controller.dto.RestResponse;
import top.focess.veto.training.TrainingResponses.*;

/**
 * Administrator-only REST API for managing the Veto SLM model training pipeline.
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
 *   <li>POST /api/v1/training/quality-check - Run quality filter on training data
 *   <li>GET /api/v1/training/evaluation - Get the latest evaluation report
 */
@RestController
@RequestMapping("/api/v1/training")
public class TrainingController {

    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.training.TrainingController");

    private final @NonNull TrainingManager trainingManager;
    private final @NonNull TrainingConfiguration config;
    private final @NonNull RequestAuthorization authorization;

    /** Create the controller with its collaborators. */
    public TrainingController(
            @NonNull TrainingManager trainingManager,
            @NonNull TrainingConfiguration config,
            @NonNull RequestAuthorization authorization) {
        this.trainingManager = trainingManager;
        this.config = config;
        this.authorization = authorization;
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
    public @NonNull ResponseEntity<RestResponse> startTraining(
            @RequestBody(required = false) TrainingRequest request) {
        authorization.requireAdmin();
        if (trainingManager.isRunning()) {
            return ResponseEntity.status(409)
                    .body(
                            new ActionProgress(
                                    false,
                                    "Training is already in progress",
                                    trainingManager.getProgress()));
        }

        boolean started = trainingManager.startTraining(request);
        if (started) {
            log.info("Training pipeline started");
            return ResponseEntity.ok(
                    new Started(
                            true,
                            "Training pipeline started",
                            config.getBaseModel(),
                            trainingManager.getProgress()));
        } else {
            return ResponseEntity.status(500)
                    .body(
                            new Failed(
                                    false,
                                    "Failed to start training pipeline. Check configuration.",
                                    trainingManager.getProgress().getErrorMessage()));
        }
    }

    /** Cancel the current training run. */
    @PostMapping("/cancel")
    public @NonNull ResponseEntity<RestResponse> cancelTraining() {
        authorization.requireAdmin();
        if (!trainingManager.isRunning()) {
            return ResponseEntity.ok(
                    new ActionProgress(
                            true, "No training in progress", trainingManager.getProgress()));
        }

        trainingManager.cancelTraining();
        log.warn("Training cancelled by user request");
        return ResponseEntity.ok(
                new ActionProgress(true, "Training cancelled", trainingManager.getProgress()));
    }

    /** Get current training progress. */
    @GetMapping("/progress")
    public @NonNull ResponseEntity<RestResponse> getProgress() {
        authorization.requireAdmin();
        TrainingProgress p = trainingManager.getProgress();
        return ResponseEntity.ok(
                new Progress(
                        p.getStatus().name(),
                        p.getProgress(),
                        p.getCurrentPhase(),
                        p.getMessage(),
                        p.getStartedAt(),
                        p.getCompletedAt(),
                        p.getTrainedModelPath(),
                        p.getErrorMessage(),
                        p.getEvaluation()));
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
    public @NonNull ResponseEntity<RestResponse> deployModel(
            @RequestBody @NonNull DeployModelRequest request) {
        authorization.requireAdmin();
        String modelPath = request.modelPath();
        if (modelPath == null || modelPath.isEmpty()) {
            String latestModel = trainingManager.getProgress().getTrainedModelPath();
            modelPath =
                    latestModel.isBlank()
                            ? config.getModelOutputDir() + "/" + config.getDefaultGgufName()
                            : latestModel;
        }

        boolean deployed = trainingManager.deployModel(modelPath);
        if (deployed) {
            log.info("Model deployed: {}", modelPath);
            return ResponseEntity.ok(
                    new Deployed(
                            true,
                            "Model deployed successfully",
                            config.getModelOutputDir() + "/" + config.getDefaultGgufName()));
        } else {
            return ResponseEntity.status(500)
                    .body(new Action(false, "Failed to deploy model. Check that the file exists."));
        }
    }

    /** Get overall training system status (configuration info). */
    @GetMapping("/status")
    public @NonNull ResponseEntity<RestResponse> getStatus() {
        authorization.requireAdmin();
        return ResponseEntity.ok(
                new Status(
                        trainingManager.isRunning(),
                        config.getBaseModel(),
                        config.getTrainingDir(),
                        config.getModelOutputDir(),
                        config.getDefaultGgufName(),
                        config.isAutoDeployOnCompletion(),
                        config.isQualityFilterEnabled(),
                        config.getMaxTrainingHours(),
                        trainingManager.getProgress()));
    }

    /**
     * Run the quality filter on existing training data without starting a training run. Returns the
     * filter report.
     */
    @PostMapping("/quality-check")
    public @NonNull ResponseEntity<RestResponse> runQualityCheck() {
        authorization.requireAdmin();
        QualityReport report = trainingManager.runStandaloneQualityCheck();
        if (report != null) {
            return ResponseEntity.ok(new Quality(true, "Quality check completed", report));
        } else {
            return ResponseEntity.status(500)
                    .body(
                            new Action(
                                    false,
                                    "Quality check failed. Training data may not exist yet."));
        }
    }

    /** Get the latest evaluation report from the most recent training run. */
    @GetMapping("/evaluation")
    public @NonNull ResponseEntity<RestResponse> getEvaluation() {
        authorization.requireAdmin();
        TrainingProgress.EvaluationReport eval = trainingManager.getProgress().getEvaluation();
        if (eval != null) {
            return ResponseEntity.ok(new Evaluation(true, eval));
        } else {
            return ResponseEntity.ok(
                    new Action(false, "No evaluation report available. Run training first."));
        }
    }
}
