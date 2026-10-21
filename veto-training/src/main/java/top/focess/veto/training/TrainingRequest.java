package top.focess.veto.training;

import java.nio.file.Path;
import org.jspecify.annotations.NonNull;

/** Task inputs; outputDirectory must not already exist. Test data is evaluated separately. */
public record TrainingRequest(
        @NonNull String baseModel,
        @NonNull Path trainingData,
        @NonNull Path validationData,
        @NonNull Path outputDirectory,
        @NonNull String modelName,
        @NonNull Options options) {
    public TrainingRequest {
        if (baseModel.isBlank() || !modelName.matches("[a-zA-Z0-9][a-zA-Z0-9_-]*")) {
            throw new IllegalArgumentException(
                    "A base model and a plain artifact name are required");
        }
        trainingData = trainingData.toAbsolutePath().normalize();
        validationData = validationData.toAbsolutePath().normalize();
        outputDirectory = outputDirectory.toAbsolutePath().normalize();
    }

    /** The same options are used for every task; task owners choose their values. */
    public record Options(
            int epochs,
            double learningRate,
            int batchSize,
            int accumulationSteps,
            int loraRank,
            int loraAlpha,
            double loraDropout,
            int sequenceLength,
            @NonNull PromptFormat promptFormat) {
        public Options {
            if (epochs <= 0
                    || !Double.isFinite(learningRate)
                    || learningRate <= 0
                    || batchSize <= 0
                    || accumulationSteps <= 0
                    || loraRank <= 0
                    || loraAlpha <= 0
                    || !Double.isFinite(loraDropout)
                    || loraDropout < 0
                    || loraDropout >= 1
                    || sequenceLength <= 0) {
                throw new IllegalArgumentException("Invalid training options");
            }
        }
    }

    public enum PromptFormat {
        RAW,
        INSTRUCTION
    }
}
