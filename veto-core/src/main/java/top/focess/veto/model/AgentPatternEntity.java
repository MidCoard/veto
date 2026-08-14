package top.focess.veto.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import top.focess.veto.model.tier.ModelBinding;
import top.focess.veto.model.tier.ModelTier;

/**
 * A named agent pattern bound to a {@link ModelTier}. The tier is the live source of truth: at
 * activation the {@link top.focess.veto.model.tier.ModelTierRegistry} resolves the tier against the
 * active model-tier configuration to obtain the concrete provider, model, and credential. Patterns
 * do not know which concrete model they run on - only their tier - so switching the active
 * configuration swaps the model for every pattern at once.
 *
 * <p>The {@code provider}/{@code model}/{@code topModel}/{@code credentialKey} columns are a
 * <em>vestigial create-time cache</em>: they are populated from the tier's resolved {@link
 * ModelBinding} when the pattern is created purely to satisfy pre-existing NOT NULL constraints
 * (Hibernate {@code ddl-auto=update} adds the {@code tier} column but cannot relax the old ones).
 * They are not read for live resolution; the registry is. A future migration may drop them.
 */
@Entity
@Table(name = "agent_patterns")
public class AgentPatternEntity {

    @Id private @NonNull String id = "";

    @Column(nullable = false)
    private @NonNull String name = "";

    @Enumerated(EnumType.STRING)
    @Column(name = "tier", nullable = false)
    private @NonNull ModelTier tier = ModelTier.TOP;

    @Column(name = "owner", nullable = false)
    private @NonNull String owner = "";

    @Column(name = "created_at", nullable = false)
    private @NonNull Instant createdAt = Instant.EPOCH;

    // ── vestigial NOT NULL cache (populated from the tier binding at create; not live-read) ──
    @Column(nullable = false)
    private @NonNull String provider = "";

    @Column(nullable = false)
    private @NonNull String model = "";

    @Column(name = "top_model", nullable = false)
    private @NonNull String topModel = "";

    @Column(name = "credential_key", nullable = false)
    private @NonNull String credentialKey = "";

    protected AgentPatternEntity() {}

    /**
     * Create a pattern bound to a tier. {@code cache} is the tier's resolved binding at create
     * time, used to populate the vestigial NOT NULL cache columns.
     *
     * @param name the pattern name
     * @param tier the model tier this pattern binds to
     * @param cache the resolved binding for {@code tier} (provider/model/credential cached)
     * @param owner the owning username
     */
    public AgentPatternEntity(
            @NonNull String name,
            @NonNull ModelTier tier,
            @NonNull ModelBinding cache,
            @NonNull String owner) {
        this.id = UUID.randomUUID().toString();
        this.name = name;
        this.tier = tier;
        this.owner = owner;
        this.createdAt = Instant.now();
        this.provider = cache.provider().name();
        this.model = cache.model();
        this.topModel = cache.model();
        this.credentialKey = cache.credentialKey();
    }

    /**
     * Transitional constructor for callers that still supply a frozen provider/model/credential
     * directly. Defaults the tier to {@link ModelTier#TOP}. Used while the agent-freeze path
     * migrates to tier-based resolution.
     */
    public AgentPatternEntity(
            @NonNull String name,
            @NonNull String provider,
            @NonNull String model,
            @NonNull String credentialKey,
            @NonNull String owner) {
        this.id = UUID.randomUUID().toString();
        this.name = name;
        this.owner = owner;
        this.createdAt = Instant.now();
        this.provider = provider;
        this.model = model;
        this.topModel = model;
        this.credentialKey = credentialKey;
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

    public @NonNull ModelTier getTier() {
        return tier;
    }

    public void setTier(@NonNull ModelTier tier) {
        this.tier = tier;
    }

    public @NonNull String getOwner() {
        return owner;
    }

    public void setOwner(@NonNull String owner) {
        this.owner = owner;
    }

    public @NonNull Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(@NonNull Instant createdAt) {
        this.createdAt = createdAt;
    }

    /** The cached provider (value of the tier binding at create; not live). */
    public @NonNull String getProvider() {
        return provider;
    }

    public void setProvider(@NonNull String provider) {
        this.provider = provider;
    }

    /** The cached model (value of the tier binding at create; not live). */
    public @NonNull String getModel() {
        return model;
    }

    public void setModel(@NonNull String model) {
        this.model = model;
    }

    /** The cached credential-key (value of the tier binding at create; not live). */
    public @NonNull String getCredentialKey() {
        return credentialKey;
    }

    public void setCredentialKey(@NonNull String credentialKey) {
        this.credentialKey = credentialKey;
    }

    /** The cached top model (mirror of {@link #getModel()}; retained for NOT NULL compat). */
    public @NonNull String getTopModel() {
        return topModel;
    }

    public void setTopModel(@NonNull String topModel) {
        this.topModel = topModel;
    }
}
