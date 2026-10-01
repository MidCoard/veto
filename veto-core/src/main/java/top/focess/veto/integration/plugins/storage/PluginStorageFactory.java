package top.focess.veto.integration.plugins.storage;

import org.jspecify.annotations.NonNull;
import top.focess.veto.api.plugin.Scope;
import top.focess.veto.api.plugin.storage.PluginStorage;
import top.focess.veto.plugin.runtime.PluginLifecycle;

/** Host-only binding factory; never included in plugin-visible host services. */
public interface PluginStorageFactory {
    /** Returns the storage bound to the given plugin activation. */
    @NonNull PluginStorage bind(@NonNull PluginLifecycle plugin);

    /** Validates the grant against its issuing binding and returns the session owner. */
    @NonNull String authorizeSession(
            @NonNull PluginStorage storage,
            PluginStorage.@NonNull Grant<Scope.@NonNull SessionScope> grant);

    /** Validates a user grant against its issuing plugin binding and live owner. */
    @NonNull String authorizeUser(
            @NonNull PluginStorage storage,
            PluginStorage.@NonNull Grant<Scope.@NonNull UserScope> grant);

    /** Validates a caller grant and issues a distinct user grant for the provider binding. */
    PluginStorage.@NonNull Grant<Scope.@NonNull UserScope> transferUser(
            @NonNull PluginStorage caller,
            PluginStorage.@NonNull Grant<Scope.@NonNull UserScope> grant,
            @NonNull PluginStorage provider);

    /** Validates a caller grant and issues a distinct session grant for the provider binding. */
    PluginStorage.@NonNull Grant<Scope.@NonNull SessionScope> transferSession(
            @NonNull PluginStorage caller,
            PluginStorage.@NonNull Grant<Scope.@NonNull SessionScope> grant,
            @NonNull PluginStorage provider);
}
