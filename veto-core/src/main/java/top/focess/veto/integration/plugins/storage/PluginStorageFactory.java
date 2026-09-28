package top.focess.veto.integration.plugins.storage;

import org.jspecify.annotations.NonNull;
import top.focess.veto.api.plugin.storage.PluginStorage;
import top.focess.veto.plugin.runtime.PluginLifecycle;

/** Host-only binding factory; never included in plugin-visible host services. */
public interface PluginStorageFactory {
    /** Returns the storage bound to the given plugin activation. */
    @NonNull PluginStorage bind(@NonNull PluginLifecycle plugin);

    /** Validates the scope against its issuing binding and returns the session owner. */
    @NonNull String authorizeSession(
            @NonNull PluginStorage storage, PluginStorage.@NonNull SessionScope scope);

    /** Validates a user scope against its issuing plugin binding and live owner. */
    @NonNull String authorizeUser(
            @NonNull PluginStorage storage, PluginStorage.@NonNull UserScope scope);

    /** Validates a caller grant and issues a distinct user grant for the provider binding. */
    PluginStorage.@NonNull UserScope transferUser(
            @NonNull PluginStorage caller,
            PluginStorage.@NonNull UserScope scope,
            @NonNull PluginStorage provider);

    /** Validates a caller grant and issues a distinct session grant for the provider binding. */
    PluginStorage.@NonNull SessionScope transferSession(
            @NonNull PluginStorage caller,
            PluginStorage.@NonNull SessionScope scope,
            @NonNull PluginStorage provider);
}
