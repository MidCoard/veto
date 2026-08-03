package top.focess.veto.training;

import org.jspecify.annotations.NonNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for the Veto Model Training Pipeline. Controls Python subprocess paths, model
 * selection, and output destinations.
 */
@Configuration
@ConfigurationProperties(prefix = "veto.training")
public class TrainingConfiguration {

    /** Python interpreter path (can be venv-resolved). */
    private @NonNull String pythonPath = "python";

    /** Root directory of the training pipeline (relative to project root). */
    private @NonNull String trainingDir = "./training";

    /** Directory where models are output and looked up. */
    private @NonNull String modelOutputDir = "./models";

    /** HuggingFace base model for fine-tuning. */
    private @NonNull String baseModel = "Qwen/Qwen2.5-1.5B-Instruct";

    /** Default GGUF filename expected by the LlamaCppBridge. */
    private @NonNull String defaultGgufName = "veto-slm.gguf";

    /** Maximum hours a training run can take before forced cancellation. */
    private int maxTrainingHours = 4;

    /** Whether to auto-deploy a trained model after completion. */
    private boolean autoDeployOnCompletion = true;

    /** Path to a Python venv activate script (e.g., training/.venv/Scripts/activate). */
    private @NonNull String venvPath = "./training/.venv";

    /** Whether to automatically restart the LlamaCppBridge after deploy. */
    private boolean restartBridgeOnDeploy = true;

    /** Whether to run the quality filter (Feature 6.3) before training. */
    private boolean qualityFilterEnabled = true;

    /** Directory for user-supplied custom training data. */
    private @NonNull String customDataDir = "";

    // ── Getters & Setters ──

    public @NonNull String getPythonPath() {
        return pythonPath;
    }

    public void setPythonPath(@NonNull String pythonPath) {
        this.pythonPath = pythonPath;
    }

    public @NonNull String getTrainingDir() {
        return trainingDir;
    }

    public void setTrainingDir(@NonNull String trainingDir) {
        this.trainingDir = trainingDir;
    }

    public @NonNull String getModelOutputDir() {
        return modelOutputDir;
    }

    public void setModelOutputDir(@NonNull String modelOutputDir) {
        this.modelOutputDir = modelOutputDir;
    }

    public @NonNull String getBaseModel() {
        return baseModel;
    }

    public void setBaseModel(@NonNull String baseModel) {
        this.baseModel = baseModel;
    }

    public @NonNull String getDefaultGgufName() {
        return defaultGgufName;
    }

    public void setDefaultGgufName(@NonNull String defaultGgufName) {
        this.defaultGgufName = defaultGgufName;
    }

    public int getMaxTrainingHours() {
        return maxTrainingHours;
    }

    public void setMaxTrainingHours(int maxTrainingHours) {
        this.maxTrainingHours = maxTrainingHours;
    }

    public boolean isAutoDeployOnCompletion() {
        return autoDeployOnCompletion;
    }

    public void setAutoDeployOnCompletion(boolean autoDeployOnCompletion) {
        this.autoDeployOnCompletion = autoDeployOnCompletion;
    }

    public @NonNull String getVenvPath() {
        return venvPath;
    }

    public void setVenvPath(@NonNull String venvPath) {
        this.venvPath = venvPath;
    }

    public boolean isRestartBridgeOnDeploy() {
        return restartBridgeOnDeploy;
    }

    public void setRestartBridgeOnDeploy(boolean restartBridgeOnDeploy) {
        this.restartBridgeOnDeploy = restartBridgeOnDeploy;
    }

    public boolean isQualityFilterEnabled() {
        return qualityFilterEnabled;
    }

    public void setQualityFilterEnabled(boolean qualityFilterEnabled) {
        this.qualityFilterEnabled = qualityFilterEnabled;
    }

    public @NonNull String getCustomDataDir() {
        return customDataDir;
    }

    public void setCustomDataDir(@NonNull String customDataDir) {
        this.customDataDir = customDataDir;
    }
}
