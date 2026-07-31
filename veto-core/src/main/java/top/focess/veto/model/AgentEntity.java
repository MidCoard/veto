package top.focess.veto.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import top.focess.veto.model.tier.ModelBinding;
import top.focess.veto.model.tier.ModelTier;

/**
 * An agent instance - a worker within a session. {@code role=PRIMARY} agents are user-created via
 * {@code /session create}; {@code role=SUB} agents are spawned by the primary (delegation).
 *
 * <p>The agent's {@link ModelTier} is frozen from the pattern at creation; the concrete provider,
 * model, and credential are resolved <em>live</em> from the active model-tier configuration at
 * activation, so switching the configuration swaps the model for existing agents. The {@code
 * provider}/{@code model}/{@code credentialKey} columns are a vestigial create-time cache (frozen
 * from the tier binding) retained for NOT NULL compatibility; they are not read for live
 * resolution.
 */
@Entity
@Table(name = "agent_instances")
public class AgentEntity {

    public enum Role {
        PRIMARY,
        SUB
    }

    @Id private String id;

    @Column(name = "session_id", nullable = false)
    private String sessionId;

    @Column(name = "pattern_id")
    private String patternId;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private Role role;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "tier", nullable = false)
    private ModelTier tier;

    // ── vestigial NOT NULL cache (frozen from the tier binding at create; not live-read) ──
    @Column(nullable = false)
    private String provider;

    @Column(nullable = false)
    private String model;

    @Column(name = "credential_key", nullable = false)
    private String credentialKey;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AgentEntity() {}

    /**
     * Create an agent bound to a tier. {@code cache} is the tier's resolved binding at create time,
     * used to populate the vestigial NOT NULL cache columns.
     *
     * @param sessionId the owning session
     * @param patternId the pattern this agent was instantiated from (null for spawned agents)
     * @param role PRIMARY (user-created) or SUB (spawned)
     * @param name the agent display name
     * @param tier the model tier (frozen; resolved live at activation)
     * @param cache the resolved binding for {@code tier} (provider/model/credential cached)
     */
    public AgentEntity(
            @NonNull String sessionId,
            @Nullable String patternId,
            @NonNull Role role,
            @NonNull String name,
            @NonNull ModelTier tier,
            @NonNull ModelBinding cache) {
        this.id = UUID.randomUUID().toString();
        this.sessionId = sessionId;
        this.patternId = patternId;
        this.role = role;
        this.name = name;
        this.tier = tier;
        this.provider = cache.provider().name();
        this.model = cache.model();
        this.credentialKey = cache.credentialKey();
        this.createdAt = Instant.now();
    }

    /**
     * Transitional constructor for callers that still supply a frozen provider/model/credential
     * directly. Defaults the tier to {@link ModelTier#TOP}.
     */
    public AgentEntity(
            @NonNull String sessionId,
            @Nullable String patternId,
            @NonNull Role role,
            @NonNull String name,
            @NonNull String provider,
            @NonNull String model,
            @NonNull String credentialKey) {
        this.id = UUID.randomUUID().toString();
        this.sessionId = sessionId;
        this.patternId = patternId;
        this.role = role;
        this.name = name;
        this.tier = ModelTier.TOP;
        this.provider = provider;
        this.model = model;
        this.credentialKey = credentialKey;
        this.createdAt = Instant.now();
    }

    public @NonNull String getId() {
        return id;
    }

    public @NonNull String getSessionId() {
        return sessionId;
    }

    public @Nullable String getPatternId() {
        return patternId;
    }

    public @NonNull Role getRole() {
        return role;
    }

    public @NonNull String getName() {
        return name;
    }

    public @NonNull ModelTier getTier() {
        return tier;
    }

    /** The cached provider (frozen at create; not live). */
    public @NonNull String getProvider() {
        return provider;
    }

    /** The cached model (frozen at create; not live). */
    public @NonNull String getModel() {
        return model;
    }

    /** The cached credential-key (frozen at create; not live). */
    public @NonNull String getCredentialKey() {
        return credentialKey;
    }

    public @NonNull Instant getCreatedAt() {
        return createdAt;
    }
}
