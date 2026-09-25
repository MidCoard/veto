package top.focess.veto.secret.detection;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.tool.ToolDocs;
import top.focess.veto.api.llm.LocalModelCompletion;
import top.focess.veto.api.llm.PromptRenderer;
import top.focess.veto.secret.api.SecretDetectionModel;

/** This plugin owns detection prompt/grammar; the host only supplies local inference resources. */
public final class MdcSecretDetectionModel implements SecretDetectionModel {
    private final @NonNull LocalModelCompletion model;
    private final @NonNull PromptRenderer prompts;
    private final @NonNull String grammar;

    /**
     * Builds a detection model over host local inference, loading the plugin-owned GBNF detection
     * grammar from the classpath.
     *
     * @param model the host local-model completion resource
     * @param prompts the host prompt renderer used to compile the detection prompt
     * @throws IllegalStateException if the bundled detection grammar is missing or unreadable
     */
    public MdcSecretDetectionModel(
            @NonNull LocalModelCompletion model, @NonNull PromptRenderer prompts) {
        this.model = model;
        this.prompts = prompts;
        try (var stream =
                ToolDocs.nonNullClass(MdcSecretDetectionModel.class)
                        .getResourceAsStream("/grammars/secret-detection.gbnf")) {
            if (stream == null) throw new IllegalStateException("Missing detection grammar");
            grammar = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot load detection grammar", failure);
        }
    }

    /** Reports availability of the underlying host local model. */
    public boolean isAvailable() {
        return model.isAvailable();
    }

    /**
     * Compiles the detection prompt with the given data and runs a grammar-constrained completion.
     *
     * @param source the prompt-template source identifier to compile
     * @param data bindings substituted into the prompt; {@code null} values are dropped
     * @return the constrained completion, or empty when the model produces none
     */
    public @NonNull Optional<String> complete(
            @NonNull String source, @NonNull Map<String, ?> data) {
        Map<String, Object> bound = new HashMap<>();
        data.forEach(
                (key, value) -> {
                    if (value != null) bound.put(key, value);
                });
        return model.complete(
                new LocalModelCompletion.Request(
                        "secret-detection", prompts.compile(source, bound), grammar));
    }
}
