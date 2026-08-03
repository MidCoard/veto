package top.focess.veto.veto;

import org.jspecify.annotations.NonNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Configuration for gateway Local SLM Veto Gateway. */
@Configuration
@ConfigurationProperties(prefix = "veto.veto-gateway")
public class VetoGatewayConfiguration {

    private boolean enabled = true;
    private @NonNull LlamaCppConfig llamaCpp = new LlamaCppConfig();
    private boolean interceptAllOutbound = true;
    private boolean redactSecrets = true;
    private boolean enforceStructuralConstraints = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public @NonNull LlamaCppConfig getLlamaCpp() {
        return llamaCpp;
    }

    public void setLlamaCpp(@NonNull LlamaCppConfig llamaCpp) {
        this.llamaCpp = llamaCpp;
    }

    public boolean isInterceptAllOutbound() {
        return interceptAllOutbound;
    }

    public void setInterceptAllOutbound(boolean interceptAllOutbound) {
        this.interceptAllOutbound = interceptAllOutbound;
    }

    public boolean isRedactSecrets() {
        return redactSecrets;
    }

    public void setRedactSecrets(boolean redactSecrets) {
        this.redactSecrets = redactSecrets;
    }

    public boolean isEnforceStructuralConstraints() {
        return enforceStructuralConstraints;
    }

    public void setEnforceStructuralConstraints(boolean enforceStructuralConstraints) {
        this.enforceStructuralConstraints = enforceStructuralConstraints;
    }

    public static class LlamaCppConfig {
        private @NonNull String modelPath = "./models/veto-slm.gguf";
        private int nCtx = 2048;
        private int nGpuLayers = 0;
        private double temperature = 0.1;
        private @NonNull String gbnfGrammarPath = "./grammars/veto-output.gbnf";

        public @NonNull String getModelPath() {
            return modelPath;
        }

        public void setModelPath(@NonNull String modelPath) {
            this.modelPath = modelPath;
        }

        public int getNCtx() {
            return nCtx;
        }

        public void setNCtx(int nCtx) {
            this.nCtx = nCtx;
        }

        public int getNGpuLayers() {
            return nGpuLayers;
        }

        public void setNGpuLayers(int nGpuLayers) {
            this.nGpuLayers = nGpuLayers;
        }

        public double getTemperature() {
            return temperature;
        }

        public void setTemperature(double temperature) {
            this.temperature = temperature;
        }

        public @NonNull String getGbnfGrammarPath() {
            return gbnfGrammarPath;
        }

        public void setGbnfGrammarPath(@NonNull String gbnfGrammarPath) {
            this.gbnfGrammarPath = gbnfGrammarPath;
        }
    }
}
