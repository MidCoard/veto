package top.focess.veto.secret.api;

import java.util.Optional;
import org.jspecify.annotations.NonNull;

/**
 * Plugin-defined model port delivered as a host service. The host supplies only a completion; the
 * plugin owns prompting and parsing, so any agent client can back it with any model.
 */
public interface SecretDetectionModel {
    boolean isAvailable();

    @NonNull Optional<String> complete(@NonNull String prompt);
}
