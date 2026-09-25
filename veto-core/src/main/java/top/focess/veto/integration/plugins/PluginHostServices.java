package top.focess.veto.integration.plugins;

import java.util.Map;
import org.jspecify.annotations.NonNull;

/**
 * Host-granted authority handed to plugins through {@code PluginContext} host services.
 * Registration never earns an entry here; the host decides what each service is.
 */
public record PluginHostServices(@NonNull Map<@NonNull Class<?>, @NonNull Object> services) {
    public PluginHostServices {
        services = Map.copyOf(services);
    }
}
