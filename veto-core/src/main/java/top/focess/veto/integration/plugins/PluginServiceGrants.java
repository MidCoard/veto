package top.focess.veto.integration.plugins;

import java.util.Map;
import org.jspecify.annotations.NonNull;
import top.focess.veto.plugin.runtime.ManagedPlugin;

/** Distribution-only optional service grants, resolved for a single plugin activation. */
@FunctionalInterface
public interface PluginServiceGrants {
    /** Returns the additional host services granted to the given plugin activation. */
    @NonNull Map<@NonNull Class<?>, @NonNull Object> forPlugin(@NonNull ManagedPlugin plugin);
}
