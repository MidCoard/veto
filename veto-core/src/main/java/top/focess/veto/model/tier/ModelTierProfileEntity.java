package top.focess.veto.model.tier;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * A named, user-owned model-tier profile. Each user creates their own profiles (e.g. {@code
 * default}, {@code premium}); exactly one of a user's profiles is {@link #active active} at a time.
 * A profile maps each {@link ModelTier} (TOP/MID/LOW/LOCAL) to a {@link ModelTierBindingEntity}
 * (provider + base URL + model + credential-key + sampling defaults) the user configures field by
 * field.
 *
 * <p>Patterns and agents bind to a tier name, never a model id; at activation the {@link
 * ModelTierRegistry} resolves the tier against the user's <em>active</em> profile to obtain the
 * concrete binding. Switching the active profile ({@code /modeltier use <profile>}) swaps the
 * concrete model for every pattern and agent that user owns, at the next resolution.
 *
 * <p>Unique on (owner, name) - a user's profile names are distinct. A user may have at most one
 * active profile (enforced by the service on {@code /modeltier use}).
 */
@Entity
@Table(
        name = "model_tier_profiles",
        uniqueConstraints = @UniqueConstraint(columnNames = {"owner", "name"}))
public class ModelTierProfileEntity {

    @Id private @NonNull String id = "";

    @Column(nullable = false)
    private @NonNull String name = "";

    @Column(nullable = false)
    private @NonNull String owner = "";

    @Column(nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false)
    private @NonNull Instant createdAt = Instant.EPOCH;

    protected ModelTierProfileEntity() {}

    /**
     * Create a profile. New profiles start inactive - the user activates one via {@code /modeltier
     * use <profile>}.
     *
     * @param name the profile name (unique per owner)
     * @param owner the owning username
     */
    public ModelTierProfileEntity(@NonNull String name, @NonNull String owner) {
        this(name, owner, false);
    }

    public ModelTierProfileEntity(@NonNull String name, @NonNull String owner, boolean active) {
        this.id = UUID.randomUUID().toString();
        this.name = name;
        this.owner = owner;
        this.active = active;
        this.createdAt = Instant.now();
    }

    public @NonNull String getId() {
        return id;
    }

    public @NonNull String getName() {
        return name;
    }

    public void setName(@NonNull String name) {
        this.name = name;
    }

    public @NonNull String getOwner() {
        return owner;
    }

    public void setOwner(@NonNull String owner) {
        this.owner = owner;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public @NonNull Instant getCreatedAt() {
        return createdAt;
    }
}
