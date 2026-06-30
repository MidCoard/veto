package top.focess.veto.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

@Entity
@Table(name = "agent_patterns")
public class AgentPatternEntity {

    @Id private String id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String provider;

    @Column(nullable = false)
    private String model;

    @Column(name = "top_model", nullable = false)
    private String topModel;

    @Column(name = "mid_model")
    private String midModel;

    @Column(name = "low_model")
    private String lowModel;

    @Column(name = "credential_key", nullable = false)
    private String credentialKey;

    @Column(name = "system_prompt", columnDefinition = "TEXT")
    private String systemPrompt;

    @Column(name = "owner", nullable = false)
    private String owner;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AgentPatternEntity() {}

    public AgentPatternEntity(
            String name,
            String provider,
            String model,
            String credentialKey,
            String systemPrompt,
            String owner,
            String topModel,
            String midModel,
            String lowModel) {
        this.id = UUID.randomUUID().toString();
        this.name = name;
        this.provider = provider;
        this.model = model;
        this.credentialKey = credentialKey;
        this.systemPrompt = systemPrompt;
        this.owner = owner;
        this.createdAt = Instant.now();
        this.topModel = topModel;
        this.midModel = midModel;
        this.lowModel = lowModel;
    }

    public AgentPatternEntity(
            String name,
            String provider,
            String model,
            String credentialKey,
            String systemPrompt,
            String owner) {
        this(name, provider, model, credentialKey, systemPrompt, owner, model, null, null);
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(@NonNull String name) {
        this.name = name;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(@NonNull String provider) {
        this.provider = provider;
    }

    public String getModel() {
        return model;
    }

    public void setModel(@NonNull String model) {
        this.model = model;
    }

    public String getCredentialKey() {
        return credentialKey;
    }

    public void setCredentialKey(@NonNull String credentialKey) {
        this.credentialKey = credentialKey;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public void setSystemPrompt(@NonNull String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }

    public String getOwner() {
        return owner;
    }

    public void setOwner(@NonNull String owner) {
        this.owner = owner;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(@NonNull Instant createdAt) {
        this.createdAt = createdAt;
    }

    public String getTopModel() {
        return topModel;
    }

    public void setTopModel(@NonNull String topModel) {
        this.topModel = topModel;
    }

    public String getMidModel() {
        return midModel;
    }

    public void setMidModel(@NonNull String midModel) {
        this.midModel = midModel;
    }

    public String getLowModel() {
        return lowModel;
    }

    public void setLowModel(@NonNull String lowModel) {
        this.lowModel = lowModel;
    }
}
