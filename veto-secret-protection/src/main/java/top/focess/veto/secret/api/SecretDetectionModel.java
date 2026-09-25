package top.focess.veto.secret.api;

import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.NonNull;

/** Plugin-internal detection adapter; never a host service or a dependency of core. */
public interface SecretDetectionModel {
    /** Whether the local detection model is currently usable for inference. */
    boolean isAvailable();

    /**
     * Runs a grammar-constrained completion over the given prompt data.
     *
     * @param source the prompt-template source identifier to compile
     * @param data bindings substituted into the prompt template
     * @return the model's completion, or empty when no result is produced
     */
    @NonNull Optional<String> complete(@NonNull String source, @NonNull Map<String, ?> data);
}
