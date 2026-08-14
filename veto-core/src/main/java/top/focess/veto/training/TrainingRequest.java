package top.focess.veto.training;

import org.jspecify.annotations.NonNull;

/**
 * Request parameters for a model training run. Passed via {@link TrainingController#startTraining}
 * to customize the training pipeline. Fields left null use the defaults from {@link
 * TrainingConfiguration}.
 *
 * <p>Mirrors the Python train.py CLI arguments for QLoRA fine-tuning.
 *
 * @param baseModel HuggingFace model ID or local path (for example, {@code
 *     Qwen/Qwen2.5-1.5B-Instruct})
 * @param epochs number of training epochs
 * @param learningRate learning rate for the AdamW optimizer
 * @param batchSize per-device training batch size
 * @param loraRank LoRA rank
 * @param dataPath path to training data JSONL, overriding the generated default
 * @param skipQualityFilter whether to skip the quality filter step
 */
public record TrainingRequest(
        String baseModel,
        Integer epochs,
        Double learningRate,
        Integer batchSize,
        Integer loraRank,
        String dataPath,
        Boolean skipQualityFilter) {

    /** Create a request with all defaults. */
    public static @NonNull TrainingRequest defaults() {
        return new TrainingRequest(null, null, null, null, null, null, null);
    }
}
