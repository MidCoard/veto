package top.focess.veto.api.plugin.contribution;

import java.util.List;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.plugin.contract.JsonValue;

/**
 * Read-only discovery of plugin-defined JSON contribution groups. The directory is live: a disabled
 * defining plugin hides its point, and a disabled contributor withdraws its entries. Returned
 * values are immutable JSON and never retain a provider classloader.
 */
public interface PluginContributionsDirectory {
    /**
     * One host-attributed entry in a plugin-defined group.
     *
     * @param id host-qualified entry ID
     * @param providerId contributing plugin ID
     * @param value bounded JSON descriptor
     */
    record Entry(
            @NonNull ContributionId id,
            @NonNull String providerId,
            JsonValue.@NonNull ObjectValue value) {}

    /**
     * Returns currently visible entries under an exact point name and major version. Missing or
     * disabled points have an empty group. Discovery grants no service authority.
     *
     * @param pointId stable contribution point identity
     * @param major exact major contract version
     * @return immutable entries currently visible to the calling plugin
     */
    @NonNull List<Entry> entries(@NonNull ContributionId pointId, int major);

    /** Directory used when the host provides no plugin-defined groups. */
    @NonNull PluginContributionsDirectory EMPTY = (pointId, major) -> List.of();
}
