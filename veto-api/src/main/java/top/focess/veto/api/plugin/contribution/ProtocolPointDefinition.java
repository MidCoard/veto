package top.focess.veto.api.plugin.contribution;

import java.util.Objects;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.plugin.contract.JsonValue;

/** Plugin-defined JSON contribution point registered through the standard meta-point. */
public abstract class ProtocolPointDefinition {
    private final @NonNull ContributionId id;
    private final int major;
    private final JsonValue.@NonNull ObjectValue entrySchema;
    private final ContributionPoint.@NonNull Cardinality cardinality;

    /**
     * Constructs an owner-qualified, versioned JSON point.
     *
     * @param id owner-qualified point identity
     * @param major positive major contract version
     * @param entrySchema schema applied to each JSON entry
     * @param cardinality allowed number of visible entries
     * @throws IllegalArgumentException when major is not positive
     */
    protected ProtocolPointDefinition(
            @NonNull ContributionId id,
            int major,
            JsonValue.@NonNull ObjectValue entrySchema,
            ContributionPoint.@NonNull Cardinality cardinality) {
        if (major < 1) throw new IllegalArgumentException("Invalid contribution point version");
        this.id = Objects.requireNonNull(id, "id");
        this.major = major;
        this.entrySchema = Objects.requireNonNull(entrySchema, "entrySchema");
        this.cardinality = Objects.requireNonNull(cardinality, "cardinality");
    }

    /**
     * Returns stable owner-qualified point identity.
     *
     * @return stable owner-qualified point identity
     */
    public final @NonNull ContributionId id() {
        return id;
    }

    /**
     * Returns positive major contract version.
     *
     * @return positive major contract version
     */
    public final int major() {
        return major;
    }

    /**
     * Returns bounded schema for each contributed JSON entry.
     *
     * @return bounded schema for each contributed JSON entry
     */
    public final JsonValue.@NonNull ObjectValue entrySchema() {
        return entrySchema;
    }

    /**
     * Returns maximum visible entries allowed by this point.
     *
     * @return maximum visible entries allowed by this point
     */
    public final ContributionPoint.@NonNull Cardinality cardinality() {
        return cardinality;
    }

    /**
     * Returns common API contract used by contributors for bounded JSON entries.
     *
     * @return common API contract used by contributors for bounded JSON entries
     */
    public final @NonNull ContributionPoint<JsonValue.@NonNull ObjectValue> point() {
        return new ContributionPoint<>(id, major, JsonValue.ObjectValue.class, cardinality);
    }
}
