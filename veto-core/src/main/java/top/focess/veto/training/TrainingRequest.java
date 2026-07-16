package top.focess.veto.training;

import org.jspecify.annotations.Nullable;

/**
 * Request parameters for a model training run. Passed via {@link TrainingController#startTraining}
 * to customize the training pipeline. Fields left null use the defaults from {@link
 * TrainingConfiguration}.
 *
 * <p>Mirrors the Python train.py CLI arguments for QLoRA fine-tuning.
 */
public record TrainingRequest(
        /** HuggingFace model ID or local path (e.g. "Qwen/Qwen2.5-1.5B-Instruct"). */
        @Nullable String baseModel,
        /** Number of training epochs. */
        @Nullable Integer epochs,
        /** Learning rate for the AdamW optimizer. */
        @Nullable Double learningRate,
        /** Per-device training batch size. */
        @Nullable Integer batchSize,
        /** LoRA rank (r). */
        @Nullable Integer loraRank,
        /** Path to training data JSONL (overrides default generated data). */
        @Nullable String dataPath,
        /** Skip the quality filter step (Feature 6.3). */
        @Nullable Boolean skipQualityFilter) {

    /** Create a request with all defaults. */
    public static TrainingRequest defaults() {
        return new TrainingRequest(null, null, null, null, null, null, null);
    }
}
