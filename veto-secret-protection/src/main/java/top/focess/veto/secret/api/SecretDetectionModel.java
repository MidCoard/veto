package top.focess.veto.secret.api;

import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.NonNull;

/**
 * Plugin-defined model port delivered as a host service. The plugin owns MDC prompt resources and
 * parsing. The host compiles the named MDC document with bound data before invoking a model; no
 * core dependency is required.
 */
public interface SecretDetectionModel {
    boolean isAvailable();

    @NonNull Optional<String> complete(@NonNull String source, @NonNull Map<String, ?> data);
}
