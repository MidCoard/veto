package top.focess.veto.training;

import jakarta.annotation.PostConstruct;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import top.focess.veto.veto.LlamaCppBridge;

/**
 * Wires the TrainingManager deploy callback to the LlamaCppBridge. When a training run completes
 * and deploys a model, this automatically restarts the llama.cpp subprocess with the new GGUF
 * model.
 */
@Configuration
public class TrainingAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(TrainingAutoConfiguration.class);

    private final @NonNull TrainingManager trainingManager;
    private final @NonNull LlamaCppBridge llamaCppBridge;

    public TrainingAutoConfiguration(
            TrainingManager trainingManager, LlamaCppBridge llamaCppBridge) {
        this.trainingManager = trainingManager;
        this.llamaCppBridge = llamaCppBridge;
    }

    @PostConstruct
    public void registerDeployCallback() {
        trainingManager.setDeployCallback(
                modelPath -> {
                    log.info(
                            "Auto-deploy triggered: restarting LlamaCppBridge with model '{}'",
                            modelPath);
                    boolean restarted = llamaCppBridge.restartWithModel(modelPath);
                    if (restarted) {
                        log.info(
                                "gateway VetoGateway: Restarted with newly trained model: {}",
                                modelPath);
                    } else {
                        log.error(
                                "gateway VetoGateway: Failed to restart with model: {}", modelPath);
                    }
                });
        log.info("Training deploy callback registered -> LlamaCppBridge");
    }
}
