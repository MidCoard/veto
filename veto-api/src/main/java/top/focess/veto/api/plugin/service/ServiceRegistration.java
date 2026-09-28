package top.focess.veto.api.plugin.service;

import org.jspecify.annotations.NonNull;

import java.util.Objects;

/**
 * Startup registration of a named JSON protocol major version and its local handler. Registration
 * publishes an implementation after validation; it grants no host authority.
 *
 * @param name public protocol name
 * @param version positive major protocol version
 * @param scope host-validated caller scope required for each call
 * @param handler provider-owned handler invoked after admission checks
 */
public record ServiceRegistration(
        @NonNull String name,
        int version,
        @NonNull ServiceScope scope,
        @NonNull ScopedServiceHandler handler) {
    /** Validates the public protocol name and major version. */
    public ServiceRegistration {
        if (name.isBlank() || name.length() > 128 || version < 1)
            throw new IllegalArgumentException("Invalid service name or version");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(handler, "handler");
    }

    /**
     * Registers an unscoped JSON handler as a global service.
     *
     * @param name public protocol name
     * @param version positive major protocol version
     * @param handler provider-owned unscoped handler
     */
    public ServiceRegistration(@NonNull String name, int version, @NonNull ServiceHandler handler) {
        this(name, version, ServiceScope.GLOBAL, (context, request) -> handler.invoke(request));
    }
}
