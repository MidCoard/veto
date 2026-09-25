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
    /** Public protocol identity and host-attributed provider plugin ID. */
    record Descriptor(@NonNull String name, int version, @NonNull String providerId) {}

    interface Handle {
        /** Describes the exact protocol and provider pinned by this handle. */
        @NonNull Descriptor descriptor();

        /** Invokes the bounded JSON protocol after current lifecycle and selection checks. */
        @NonNull JsonValue invoke(@NonNull JsonValue request) throws ServiceException;
    }

    /** Returns the currently published descriptors visible to this caller. */
    @NonNull List<Descriptor> available();

    /** Exact name and major-version lookup; absence is a supported availability result. */
    @NonNull Optional<Handle> find(@NonNull String name, int version);

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
