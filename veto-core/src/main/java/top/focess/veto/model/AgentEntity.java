package top.focess.veto.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * An agent instance - a worker within a session. {@code role=PRIMARY} agents are user-created via
 * {@code /session create}; {@code role=SUB} agents are spawned by the primary (delegation -
 * future).
 *
 * <p>Config fields (provider/model/credentialKey) are frozen from the pattern at creation, so
 * editing the pattern later does not mutate existing agents.
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

    @Column(nullable = false)
    private String provider;

    @Column(nullable = false)
    private String model;

    @Column(name = "credential_key", nullable = false)
    private String credentialKey;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AgentEntity() {}

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

    public @NonNull String getProvider() {
        return provider;
    }

    public @NonNull String getModel() {
        return model;
    }

    public @NonNull String getCredentialKey() {
        return credentialKey;
    }

    public @NonNull Instant getCreatedAt() {
        return createdAt;
    }
}
