package top.focess.veto.training;

import java.time.Instant;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

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

    private volatile @NonNull Status status = Status.IDLE;
    private volatile double progress = 0.0;
    private volatile @NonNull String currentPhase = "";
    private volatile @NonNull String message = "";
    private volatile @NonNull String trainedModelPath = "";
    private volatile @Nullable Instant startedAt = null;
    private volatile @Nullable Instant completedAt = null;
    private volatile @NonNull String errorMessage = "";

    /** Optional evaluation report attached after evaluation. */
    private volatile @Nullable EvaluationReport evaluation = null;

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

    public void complete(@NonNull String modelPath) {
        this.status = Status.COMPLETED;
        this.progress = 1.0;
        this.currentPhase = "completed";
        this.message = "Training completed successfully";
        this.trainedModelPath = modelPath;
        this.completedAt = Instant.now();
    }

    public void fail(@NonNull String error) {
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

    public void updatePhase(@NonNull String phase, double progress, @NonNull String message) {
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

    public @NonNull Status getStatus() {
        return status;
    }

    public double getProgress() {
        return progress;
    }

    public @NonNull String getCurrentPhase() {
        return currentPhase;
    }

    public @NonNull String getMessage() {
        return message;
    }

    public @NonNull String getTrainedModelPath() {
        return trainedModelPath;
    }

    public @Nullable Instant getStartedAt() {
        return startedAt;
    }

    public @Nullable Instant getCompletedAt() {
        return completedAt;
    }

    public @NonNull String getErrorMessage() {
        return errorMessage;
    }

    public @Nullable EvaluationReport getEvaluation() {
        return evaluation;
    }

    public void setEvaluation(@NonNull EvaluationReport evaluation) {
        this.evaluation = evaluation;
    }

    // ── Record for evaluation report (mirrors Python schema) ──

    public record EvaluationReport(
            @Nullable String modelPath,
            @Nullable String datasetPath,
            @Nullable String timestamp,
            int totalSamples,
            double elapsedSeconds,
            @NonNull GbnfCompliance gbnfCompliance,
            @NonNull DecisionAccuracy decisionAccuracy,
            @NonNull RedactionAccuracy redactionAccuracy,
            @NonNull StructuralValidation structuralValidation) {
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
