package top.focess.veto.api.plugin.service;

import org.jspecify.annotations.NonNull;

/** A named contract major version and its local implementation. */
public record ServiceRegistration(
        @NonNull String name, int version, @NonNull ServiceHandler handler) {
    public ServiceRegistration {
        if (name.isBlank() || name.length() > 128 || version < 1)
            throw new IllegalArgumentException("Invalid service name or version");
    }
}
