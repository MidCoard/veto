package top.focess.veto.model.tier;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
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
 * <p>Unique on (userId, name) - a user's profile names are distinct. A user may have at most one
 * active profile (enforced by the service on {@code /modeltier use}).
 */
@Entity
@Table(
        name = "model_tier_profiles",
        uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "name"}))
public class ModelTierProfileEntity {

    @Id private @NonNull String id = "";

    @Column(nullable = false)
    private @NonNull String name = "";

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "user_id", nullable = false)
    private @NonNull UUID userId = new UUID(0, 0);

    @Column(nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false)
    private @NonNull Instant createdAt = Instant.EPOCH;

    /** JPA no-arg constructor. */
    protected ModelTierProfileEntity() {}

    /**
     * Create a profile. New profiles start inactive - the user activates one via {@code /modeltier
     * use <profile>}.
     *
     * @param name the profile name (unique per user)
     * @param userId the owning userId
     */
    public ModelTierProfileEntity(@NonNull String name, @NonNull UUID userId) {
        this(name, userId, false);
    }

    /**
     * Create a profile with an explicit active flag (used to auto-activate a user's first profile).
     *
     * @param name the profile name (unique per user)
     * @param userId the owning userId
     * @param active whether the profile starts active
     */
    public ModelTierProfileEntity(@NonNull String name, @NonNull UUID userId, boolean active) {
        this.id = UUID.randomUUID().toString();
        this.name = name;
        this.userId = userId;
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

    public @NonNull UUID getUserId() {
        return userId;
    }

    public void setUserId(@NonNull UUID userId) {
        this.userId = userId;
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
