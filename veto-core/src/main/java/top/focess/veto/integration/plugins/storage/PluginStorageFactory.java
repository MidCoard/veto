package top.focess.veto.integration.plugins.storage;

import org.checkerframework.framework.qual.DefaultQualifier;
import org.checkerframework.framework.qual.TypeUseLocation;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.NullMarked;
import top.focess.veto.api.plugin.storage.PluginStorage;
import top.focess.veto.plugin.runtime.ManagedPlugin;

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
    PluginStorage bind(ManagedPlugin plugin);

    String authorizeSession(PluginStorage storage, PluginStorage.SessionScope scope);
}
