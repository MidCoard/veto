package top.focess.veto.training;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for the Veto Model Training Pipeline. Controls Python subprocess paths, model
 * selection, and output destinations.
 */
@Configuration
@ConfigurationProperties(prefix = "veto.training")
public class TrainingConfiguration {

    /**
     * Python interpreter path (can be venv-resolved).
     */
    private String pythonPath = "python";

    /** Root directory of the training pipeline (relative to project root). */
    private String trainingDir = "./training";

    /** Directory where models are output and looked up. */
    private String modelOutputDir = "./models";

    /** HuggingFace base model for fine-tuning. */
    private String baseModel = "Qwen/Qwen2.5-1.5B-Instruct";

    /** Default GGUF filename expected by the LlamaCppBridge. */
    private String defaultGgufName = "veto-slm.gguf";

    /** Maximum hours a training run can take before forced cancellation. */
    private int maxTrainingHours = 4;

    /** Whether to auto-deploy a trained model after completion. */
    private boolean autoDeployOnCompletion = true;

    /** Path to a Python venv activate script (e.g., training/.venv/Scripts/activate). */
    private String venvPath = "./training/.venv";

    /** Whether to automatically restart the LlamaCppBridge after deploy. */
    private boolean restartBridgeOnDeploy = true;

    // ── Getters & Setters ──

    public String getPythonPath() {
        return pythonPath;
    }

    public void setPythonPath(String pythonPath) {
        this.pythonPath = pythonPath;
    }

    public String getTrainingDir() {
        return trainingDir;
    }

    public void setTrainingDir(String trainingDir) {
        this.trainingDir = trainingDir;
    }

    public String getModelOutputDir() {
        return modelOutputDir;
    }

    public void setModelOutputDir(String modelOutputDir) {
        this.modelOutputDir = modelOutputDir;
    }

    public String getBaseModel() {
        return baseModel;
    }

    public void setBaseModel(String baseModel) {
        this.baseModel = baseModel;
    }

    public String getDefaultGgufName() {
        return defaultGgufName;
    }

    public void setDefaultGgufName(String defaultGgufName) {
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

    public String getVenvPath() {
        return venvPath;
    }

    public void setVenvPath(String venvPath) {
        this.venvPath = venvPath;
    }

    public boolean isRestartBridgeOnDeploy() {
        return restartBridgeOnDeploy;
    }

    public void setRestartBridgeOnDeploy(boolean restartBridgeOnDeploy) {
        this.restartBridgeOnDeploy = restartBridgeOnDeploy;
    }
}
