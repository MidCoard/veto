package top.focess.veto.api.plugin.service;

import org.jspecify.annotations.NonNull;

/**
 * Startup registration of a named JSON protocol major version and its local handler. Registration
 * publishes an implementation after validation; it grants no host authority.
 *
 * @param name public protocol name
 * @param version positive major protocol version
 * @param handler provider-owned handler invoked after admission checks
 */
public record ServiceRegistration(
        @NonNull String name, int version, @NonNull ServiceHandler handler) {
    /** Validates the public protocol name and major version. */
    public ServiceRegistration {
        if (name.isBlank() || name.length() > 128 || version < 1)
            throw new IllegalArgumentException("Invalid service name or version");
    }
}
