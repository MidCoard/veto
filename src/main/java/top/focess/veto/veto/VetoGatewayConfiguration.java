package top.focess.veto.veto;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Configuration for C7 Local SLM Veto Gateway. */
@Configuration
@ConfigurationProperties(prefix = "veto.veto-gateway")
public class VetoGatewayConfiguration {

  private boolean enabled = true;
  private LlamaCppConfig llamaCpp = new LlamaCppConfig();
  private boolean interceptAllOutbound = true;
  private boolean redactSecrets = true;
  private boolean enforceStructuralConstraints = true;

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public LlamaCppConfig getLlamaCpp() {
    return llamaCpp;
  }

  public void setLlamaCpp(LlamaCppConfig llamaCpp) {
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
    private String modelPath = "${VETO_LLAMA_MODEL_PATH:./models/veto-slm.gguf}";
    private int nCtx = 2048;
    private int nGpuLayers = 0;
    private double temperature = 0.1;
    private String gbnfGrammarPath = "./grammars/veto-output.gbnf";

    public String getModelPath() {
      return modelPath;
    }

    public void setModelPath(String modelPath) {
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

    public String getGbnfGrammarPath() {
      return gbnfGrammarPath;
    }

    public void setGbnfGrammarPath(String gbnfGrammarPath) {
      this.gbnfGrammarPath = gbnfGrammarPath;
    }
  }
}
