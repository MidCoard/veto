package top.focess.veto.integration.plugins.storage;

import org.checkerframework.framework.qual.DefaultQualifier;
import org.checkerframework.framework.qual.TypeUseLocation;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.NullMarked;

import top.focess.veto.api.plugin.storage.PluginStorage;
import top.focess.veto.plugin.runtime.PluginLifecycle;

/** Host-only binding factory; never included in plugin-visible host services. */
@NullMarked
@DefaultQualifier(
        value = NonNull.class,
        locations = {
            TypeUseLocation.FIELD,
            TypeUseLocation.PARAMETER,
            TypeUseLocation.RETURN,
            TypeUseLocation.UPPER_BOUND
        })
public interface PluginStorageFactory {
    /** Returns the storage bound to the given plugin activation. */
    PluginStorage bind(PluginLifecycle plugin);

    /** Validates the scope against its issuing binding and returns the session owner. */
    String authorizeSession(PluginStorage storage, PluginStorage.SessionScope scope);

    /** Validates a user scope against its issuing plugin binding and live owner. */
    String authorizeUser(PluginStorage storage, PluginStorage.UserScope scope);

    /** Validates a caller grant and issues a distinct user grant for the provider binding. */
    PluginStorage.UserScope transferUser(
            PluginStorage caller, PluginStorage.UserScope scope, PluginStorage provider);

    /** Validates a caller grant and issues a distinct session grant for the provider binding. */
    PluginStorage.SessionScope transferSession(
            PluginStorage caller, PluginStorage.SessionScope scope, PluginStorage provider);
}
