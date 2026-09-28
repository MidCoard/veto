package top.focess.veto.api.plugin.contribution;

import org.jspecify.annotations.NonNull;

import top.focess.veto.api.plugin.contract.JsonValue;

import java.util.Objects;

/**
 * A plugin-defined, JSON-only contribution point. Register this definition at the standard {@code
 * veto:contributions} point before other plugins publish entries at {@link #point()}. The host
 * attributes ownership from the defining plugin, not from a claimed JSON field.
 *
 * @param id stable owner-qualified point name
 * @param major major contract version
 * @param entrySchema bounded JSON schema for each entry
 * @param cardinality maximum number of visible entries
 */
public record ProtocolPointDefinition(
        @NonNull ContributionId id,
        int major,
        JsonValue.@NonNull ObjectValue entrySchema,
        ContributionPoint.@NonNull Cardinality cardinality) {
    /** Checks the major version; the host validates schema and point ownership at activation. */
    public ProtocolPointDefinition {
        if (major < 1) throw new IllegalArgumentException("Invalid contribution point version");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(entrySchema, "entrySchema");
        Objects.requireNonNull(cardinality, "cardinality");
    }

    /**
     * Returns the common API contract for registering bounded JSON entries at this point.
     *
     * @return the point used by contributors
     */
    public @NonNull ContributionPoint<JsonValue.@NonNull ObjectValue> point() {
        return new ContributionPoint<>(
                id, major, JsonValue.ObjectValue.class, cardinality);
    }
}
