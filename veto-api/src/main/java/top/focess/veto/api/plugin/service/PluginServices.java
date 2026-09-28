package top.focess.veto.api.plugin.service;

import org.jspecify.annotations.NonNull;

import top.focess.veto.api.plugin.contract.JsonValue;
import top.focess.veto.api.plugin.storage.PluginStorage;

import java.util.List;
import java.util.Optional;

/**
 * Named JSON service discovery for plugin-to-plugin protocols.
 *
 * <p>The host binds the directory after all plugins initialize. Consumers discover services at
 * start or later. This is separate from class-keyed host capabilities in {@code
 * PluginContext.service}; provider implementation classes never cross this boundary. Retained
 * handles recheck caller and provider admission on every invocation. A retained handle keeps a
 * descriptor, not the provider implementation or classloader; revocation makes invocation
 * unavailable even if the consumer keeps its handle.
 */
public interface PluginServices {
    /**
     * Public protocol identity and host-attributed provider plugin ID.
     *
     * @param name protocol name
     * @param version exact major protocol version
     * @param providerId host-attributed provider plugin ID
     * @param scope identity boundary required for each invocation
     */
    record Descriptor(
            @NonNull String name,
            int version,
            @NonNull String providerId,
            @NonNull ServiceScope scope) {}

    /** Revocable handle pinned to one exact provider registration. */
    interface Handle {
        /**
         * Describes the protocol and provider pinned by this handle.
         *
         * @return the exact protocol and provider pinned by this handle
         */
        @NonNull Descriptor descriptor();

        /**
         * Invokes the bounded JSON protocol after current lifecycle and selection checks.
         *
         * @param request bounded JSON request
         * @return the provider's bounded JSON response
         * @throws ServiceException when admission, validation, timeout, or provider execution fails
         */
        @NonNull JsonValue invoke(@NonNull JsonValue request) throws ServiceException;

        /**
         * Invokes a user- or session-scoped service with a host-issued scope. The host revalidates
         * the token against the calling plugin and current owner/session on every call.
         *
         * @param scope caller-owned host grant matching the service's declared scope
         * @param request bounded JSON request
         * @return bounded JSON response
         * @throws ServiceException when admission, scope validation, or execution fails
         */
        @NonNull JsonValue invoke(PluginStorage.@NonNull Scope scope, @NonNull JsonValue request)
                throws ServiceException;
    }

    /** Host-issued registration for one provider-owned JSON callback. */
    interface CallbackRegistration extends AutoCloseable {
        /** Opaque callback reference safe to pass in a JSON service request. */
        @NonNull String id();

        /** Withdraws this callback; plugin unload also withdraws it automatically. */
        @Override
        void close();
    }

    /** Revocable callback handle that retains only an opaque identity. */
    interface CallbackHandle {
        /** Host-attributed plugin that registered the callback. */
        @NonNull String providerId();

        /** Invokes the callback under current caller/provider admission. */
        @NonNull JsonValue invoke(@NonNull JsonValue request) throws ServiceException;
    }

    /**
     * Lists services currently visible to this caller.
     *
     * @return currently published descriptors visible to this caller
     */
    @NonNull List<Descriptor> available();

    /**
     * Performs an exact name and major-version lookup.
     *
     * @param name public protocol name
     * @param version exact major protocol version
     * @return the matching revocable handle, or an empty value when unavailable
     */
    @NonNull Optional<Handle> find(@NonNull String name, int version);

    /** Registers a JSON callback owned by this plugin, revoked automatically on unload. */
    @NonNull CallbackRegistration registerCallback(@NonNull ServiceHandler handler);

    /** Resolves an opaque callback reference without importing its implementation class. */
    @NonNull Optional<CallbackHandle> findCallback(@NonNull String id);

    /** Directory used when no named services are available; it is always empty. */
    PluginServices EMPTY =
            new PluginServices() {
                public @NonNull List<Descriptor> available() {
                    return List.of();
                }

                public @NonNull Optional<Handle> find(@NonNull String name, int version) {
                    return Optional.empty();
                }

                public @NonNull CallbackRegistration registerCallback(
                        @NonNull ServiceHandler handler) {
                    throw new IllegalStateException("Callback registration is unavailable");
                }

                public @NonNull Optional<CallbackHandle> findCallback(@NonNull String id) {
                    return Optional.empty();
                }
            };
}
