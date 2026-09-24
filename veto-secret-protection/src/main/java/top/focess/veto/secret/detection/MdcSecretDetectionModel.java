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

    public boolean isAvailable() {
        return model.isAvailable();
    }

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
