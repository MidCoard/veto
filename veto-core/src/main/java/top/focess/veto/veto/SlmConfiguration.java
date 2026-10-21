package top.focess.veto.veto;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Shared local-model configuration used by every SLM-backed security component. */
@Configuration
@ConfigurationProperties(prefix = "veto.slm")
public class SlmConfiguration {

    private boolean enabled = true;
    private @NonNull String executablePath = "llama-server";
    private @NonNull String modelPath = "./models/veto-slm.gguf";
    private int nCtx = 2048;
    private int nGpuLayers = 99;
    private double temperature = 0.0;
    private @NonNull String gbnfGrammarPath = "./grammars/veto-output.gbnf";
    private @NonNull Map<@NonNull String, @NonNull String> pluginModelPaths = Map.of();

    /** Host-selected artifacts keyed by trusted plugin identity, never request-supplied paths. */
    public @NonNull Map<@NonNull String, @NonNull String> getPluginModelPaths() {
        return pluginModelPaths;
    }

    public void setPluginModelPaths(@NonNull Map<@NonNull String, @NonNull String> paths) {
        pluginModelPaths = Map.copyOf(paths);
    }

    /**
     * Independent process settings for a dedicated model; nested routing is intentionally absent.
     */
    public @NonNull SlmConfiguration forModel(@NonNull String path) {
        var dedicated = new SlmConfiguration();
        dedicated.setEnabled(enabled);
        dedicated.setExecutablePath(executablePath);
        dedicated.setModelPath(path);
        dedicated.setNCtx(nCtx);
        dedicated.setNGpuLayers(nGpuLayers);
        dedicated.setTemperature(temperature);
        dedicated.setGbnfGrammarPath(gbnfGrammarPath);
        return dedicated;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public @NonNull String getExecutablePath() {
        return executablePath;
    }

    public void setExecutablePath(@NonNull String executablePath) {
        this.executablePath = executablePath;
    }

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

    /**
     * Resolve a portable relative SLM resource path. Packaged runs normally find the resource under
     * their working directory, while Gradle module runs may need to find it under an ancestor
     * project directory.
     */
    public @NonNull Path resolvePath(@NonNull String configuredPath) {
        Path path = Path.of(configuredPath).normalize();
        if (path.isAbsolute()) {
            return path;
        }

        Path workingDirectory = Path.of("").toAbsolutePath().normalize();
        Path directory = workingDirectory;
        while (directory != null) {
            Path candidate = directory.resolve(path).normalize();
            if (Files.exists(candidate)) {
                return candidate;
            }
            directory = directory.getParent();
        }
        return workingDirectory.resolve(path).normalize();
    }
}
