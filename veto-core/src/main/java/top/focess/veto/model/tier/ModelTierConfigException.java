package top.focess.veto.model.tier;

import org.jspecify.annotations.NonNull;

/**
 * Thrown when a model-tier resolution cannot be satisfied for a user - the user has no active
 * profile, the profile has no binding for the requested tier, or the binding is incomplete (a
 * required field - provider, model, or credential-key - is unset).
 *
 * <p>This is fail-fast: every tier resolution (pattern create, session activate, group spawn)
 * requires the user to have an active profile with that tier fully configured. The message tells
 * the user which step is missing so they can finish setup via {@code /modeltier}.
 */
public class ModelTierConfigException extends RuntimeException {

    public ModelTierConfigException(@NonNull String message) {
        super(message);
    }
}
