package top.focess.veto.api.plugin.service;

import org.jspecify.annotations.NonNull;

/**
 * Startup registration of a named JSON protocol major version and its local handler. Registration
 * publishes an implementation after validation; it grants no host authority.
 */
public record ServiceRegistration(
        @NonNull String name, int version, @NonNull ServiceHandler handler) {
    public ServiceRegistration {
        if (name.isBlank() || name.length() > 128 || version < 1)
            throw new IllegalArgumentException("Invalid service name or version");
    }
}
