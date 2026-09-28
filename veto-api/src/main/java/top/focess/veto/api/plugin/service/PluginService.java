package top.focess.veto.api.plugin.service;

import java.util.Objects;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.plugin.contract.JsonValue;

/**
 * A provider-owned named JSON service contributed at the {@code SERVICES} point.
 *
 * <p>The host owns publication, caller admission, and revocation. The implementation owns its
 * protocol and behavior, and is closed with its contributing plugin.
 */
public abstract class PluginService {
    private final @NonNull String name;
    private final int version;
    private final @NonNull ServiceScope scope;

    /**
     * Creates a service with a stable protocol identity and required caller scope.
     *
     * @param name public protocol name
     * @param version positive major protocol version
     * @param scope required caller scope
     * @throws IllegalArgumentException when the name or version is invalid
     */
    protected PluginService(@NonNull String name, int version, @NonNull ServiceScope scope) {
        if (name.isBlank() || name.length() > 128 || version < 1)
            throw new IllegalArgumentException("Invalid service name or version");
        this.name = name;
        this.version = version;
        this.scope = Objects.requireNonNull(scope, "scope");
    }

    /**
     * Returns public protocol name.
     *
     * @return public protocol name
     */
    public final @NonNull String name() {
        return name;
    }

    /**
     * Returns positive major protocol version.
     *
     * @return positive major protocol version
     */
    public final int version() {
        return version;
    }

    /**
     * Returns caller scope validated by the host on each invocation.
     *
     * @return caller scope validated by the host on each invocation
     */
    public final @NonNull ServiceScope scope() {
        return scope;
    }

    /**
     * Handles one admitted JSON request without accessing the caller's Java classes.
     *
     * @param caller host-validated caller identity and scope
     * @param request bounded JSON request
     * @return bounded JSON response
     * @throws ServiceException when the request cannot be served
     */
    public abstract @NonNull JsonValue invoke(
            @NonNull ServiceCallContext caller, @NonNull JsonValue request) throws ServiceException;
}
