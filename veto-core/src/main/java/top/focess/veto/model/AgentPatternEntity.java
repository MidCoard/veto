package top.focess.veto.model;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "agent_patterns")
public class AgentPatternEntity {

    @Id
    private String id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String provider;

    @Column(nullable = false)
    private String model;

    @Column(name = "credential_key", nullable = false)
    private String credentialKey;

    @Column(name = "system_prompt", columnDefinition = "TEXT")
    private String systemPrompt;

    @Column(name = "owner", nullable = false)
    private String owner;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AgentPatternEntity() {
    }

    public AgentPatternEntity(
            String name,
            String provider,
            String model,
            String credentialKey,
            String systemPrompt,
            String owner) {
        this.id = UUID.randomUUID().toString();
        this.name = name;
        this.provider = provider;
        this.model = model;
        this.credentialKey = credentialKey;
        this.systemPrompt = systemPrompt;
        this.owner = owner;
        this.createdAt = Instant.now();
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getCredentialKey() {
        return credentialKey;
    }

    public void setCredentialKey(String credentialKey) {
        this.credentialKey = credentialKey;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }

    public String getOwner() {
        return owner;
    }

    public void setOwner(String owner) {
        this.owner = owner;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
