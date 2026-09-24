package top.focess.veto.api.plugin.service;

import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.plugin.contract.JsonValue;

/** Named service discovery. Retained handles recheck admission on every invocation. */
public interface PluginServices {
    record Descriptor(@NonNull String name, int version, @NonNull String providerId) {}

    interface Handle {
        @NonNull Descriptor descriptor();

        @NonNull JsonValue invoke(@NonNull JsonValue request) throws ServiceException;
    }

    @NonNull List<Descriptor> available();

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
