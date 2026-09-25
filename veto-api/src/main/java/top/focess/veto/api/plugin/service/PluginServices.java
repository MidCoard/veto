package top.focess.veto.api.plugin.service;

import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.plugin.contract.JsonValue;

/**
 * Named JSON service discovery for plugin-to-plugin protocols.
 *
 * <p>The host binds the directory after all plugins initialize. Consumers discover services at
 * start or later. This is separate from class-keyed host capabilities in {@code
 * PluginContext.service}; provider implementation classes never cross this boundary. Retained
 * handles recheck caller and provider admission on every invocation.
 */
public interface PluginServices {
    /**
     * Public protocol identity and host-attributed provider plugin ID.
     *
     * @param name protocol name
     * @param version exact major protocol version
     * @param providerId host-attributed provider plugin ID
     */
    record Descriptor(@NonNull String name, int version, @NonNull String providerId) {}

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

    /** Directory used when no named services are available; it is always empty. */
    PluginServices EMPTY =
            new PluginServices() {
                public @NonNull List<Descriptor> available() {
                    return List.of();
                }

                public @NonNull Optional<Handle> find(@NonNull String name, int version) {
                    return Optional.empty();
                }
            };
}
