package top.focess.veto.training;

import java.time.Instant;

/**
 * Represents the current state of a model training run. Thread-safe; read by the REST controller
 * for monitoring.
 */
public class TrainingProgress {

    /** Training pipeline states. */
    public enum Status {
        IDLE,
        PREPARING_DATA,
        TRAINING,
        CONVERTING,
        EVALUATING,
        COMPLETED,
        FAILED,
        CANCELLED
    }

    private volatile Status status = Status.IDLE;
    private volatile double progress = 0.0;
    private volatile String currentPhase = "";
    private volatile String message = "";
    private volatile String trainedModelPath = "";
    private volatile Instant startedAt = null;
    private volatile Instant completedAt = null;
    private volatile String errorMessage = "";

    /** Optional evaluation report attached after evaluation. */
    private volatile EvaluationReport evaluation = null;

    public TrainingProgress() {}

    // ── Mutators ──

    public void start() {
        this.status = Status.PREPARING_DATA;
        this.progress = 0.0;
        this.currentPhase = "preparing_data";
        this.message = "Starting training pipeline...";
        this.startedAt = Instant.now();
        this.completedAt = null;
        this.errorMessage = "";
        this.trainedModelPath = "";
        this.evaluation = null;
    }

    public void complete(String modelPath) {
        this.status = Status.COMPLETED;
        this.progress = 1.0;
        this.currentPhase = "completed";
        this.message = "Training completed successfully";
        this.trainedModelPath = modelPath;
        this.completedAt = Instant.now();
    }

    public void fail(String error) {
        this.status = Status.FAILED;
        this.currentPhase = "failed";
        this.message = "Training failed: " + error;
        this.errorMessage = error;
        this.completedAt = Instant.now();
    }

    public void cancel() {
        this.status = Status.CANCELLED;
        this.currentPhase = "cancelled";
        this.message = "Training cancelled by user";
        this.completedAt = Instant.now();
    }

    public void updatePhase(String phase, double progress, String message) {
        this.currentPhase = phase;
        this.progress = progress;
        this.message = message;

        // Derive status from phase name
        switch (phase) {
            case "preparing_data" -> this.status = Status.PREPARING_DATA;
            case "training" -> this.status = Status.TRAINING;
            case "converting" -> this.status = Status.CONVERTING;
            case "evaluating" -> this.status = Status.EVALUATING;
        }
    }

    // ── Getters ──

    public Status getStatus() {
        return status;
    }

    public double getProgress() {
        return progress;
    }

    public String getCurrentPhase() {
        return currentPhase;
    }

    public String getMessage() {
        return message;
    }

    public String getTrainedModelPath() {
        return trainedModelPath;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public EvaluationReport getEvaluation() {
        return evaluation;
    }

    public void setEvaluation(EvaluationReport evaluation) {
        this.evaluation = evaluation;
    }

    // ── Record for evaluation report (mirrors Python schema) ──

    public record EvaluationReport(
            String modelPath,
            String datasetPath,
            String timestamp,
            int totalSamples,
            double elapsedSeconds,
            GbnfCompliance gbnfCompliance,
            DecisionAccuracy decisionAccuracy,
            RedactionAccuracy redactionAccuracy,
            StructuralValidation structuralValidation) {
        public record GbnfCompliance(int validJsonCount, double validJsonRate) {}

        public record DecisionAccuracy(int correct, int total, double accuracy) {}

        public record RedactionAccuracy(
                int truePositives,
                int falsePositives,
                int falseNegatives,
                double precision,
                double recall,
                double f1) {}

        public record StructuralValidation(int correct, int total, double accuracy) {}
    }
}
