package top.focess.veto.training;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import org.jspecify.annotations.*;
import top.focess.veto.controller.dto.RestResponse;

/** Typed HTTP representations of training operations. */
public final class TrainingResponses {
    private TrainingResponses() {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Action(boolean success, @NonNull String message) implements RestResponse {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ActionProgress(
            boolean success, @NonNull String message, @NonNull TrainingProgress progress)
            implements RestResponse {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Started(
            boolean success,
            @NonNull String message,
            @NonNull String baseModel,
            @NonNull TrainingProgress progress)
            implements RestResponse {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Failed(boolean success, @NonNull String message, @NonNull String error)
            implements RestResponse {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Deployed(boolean success, @NonNull String message, @NonNull String targetPath)
            implements RestResponse {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Quality(boolean success, @NonNull String message, @NonNull QualityReport report)
            implements RestResponse {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Evaluation(boolean success, TrainingProgress.@NonNull EvaluationReport evaluation)
            implements RestResponse {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Status(
            boolean running,
            @NonNull String baseModel,
            @NonNull String trainingDir,
            @NonNull String modelOutputDir,
            @NonNull String defaultGgufName,
            boolean autoDeployOnCompletion,
            boolean qualityFilterEnabled,
            int maxTrainingHours,
            @NonNull TrainingProgress progress)
            implements RestResponse {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Progress(
            @NonNull String status,
            double progress,
            @NonNull String phase,
            @NonNull String message,
            @Nullable Instant startedAt,
            @Nullable Instant completedAt,
            @NonNull String trainedModelPath,
            @NonNull String error,
            TrainingProgress.@Nullable EvaluationReport evaluation)
            implements RestResponse {}
}
