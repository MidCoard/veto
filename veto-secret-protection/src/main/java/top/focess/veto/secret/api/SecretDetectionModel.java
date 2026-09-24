package top.focess.veto.secret.api;

import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.NonNull;

/** Plugin-internal detection adapter; never a host service or a dependency of core. */
public interface SecretDetectionModel {
    boolean isAvailable();

    @NonNull Optional<String> complete(@NonNull String source, @NonNull Map<String, ?> data);
}
