package top.focess.veto.model.tier;

import jakarta.persistence.*;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import top.focess.veto.llm.core.ProviderType;

/**
 * One tier's concrete binding within a user's {@link ModelTierProfileEntity}. Built field by field
 * via {@code /modeltier set <profile> <tier> <field> <value>}, so every field is independently
 * nullable - a partial row is allowed while the user is still configuring the tier. {@link
 * ModelTierRegistry#resolve} fail-fasts with a {@link ModelTierConfigException} if a required field
 * (provider, model, credential-key) is still null at resolution time.
 *
 * <p>Unique on (profileId, tier) - one binding per tier per profile.
 *
 * @param profileId the owning profile (FK to {@link ModelTierProfileEntity#getId()})
 * @param tier the model tier this binding configures
 * @param provider the LLM provider (nullable until {@code /modeltier set ... provider ...})
 * @param baseUrl the base-URL override (null -> provider default)
 * @param model the model id (nullable until set)
 * @param credentialKey vault SECURE_NOTE title holding the API key (nullable until set)
 * @param temperature the sampling temperature (nullable -> registry default)
 * @param maxOutputTokens the max output tokens (nullable -> registry default)
 */
@Entity
@Table(
        name = "model_tier_bindings",
        uniqueConstraints = @UniqueConstraint(columnNames = {"profile_id", "tier"}))
public class ModelTierBindingEntity {

    @Id private @NonNull String id = "";

    @Column(name = "profile_id", nullable = false)
    private @NonNull String profileId = "";

    @Enumerated(EnumType.STRING)
    @Column(name = "tier", nullable = false)
    private @NonNull ModelTier tier = ModelTier.TOP;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider")
    private ProviderType provider;

    @Column(name = "base_url")
    private String baseUrl;

    @Column(name = "model")
    private String model;

    @Column(name = "credential_key")
    private String credentialKey;

    @Column(name = "temperature")
    private Double temperature;

    @Column(name = "max_output_tokens")
    private Integer maxOutputTokens;

    protected ModelTierBindingEntity() {}

    public ModelTierBindingEntity(@NonNull String profileId, @NonNull ModelTier tier) {
        this.id = UUID.randomUUID().toString();
        this.profileId = profileId;
        this.tier = tier;
    }

    public @NonNull String getId() {
        return id;
    }

    public @NonNull String getProfileId() {
        return profileId;
    }

    public @NonNull ModelTier getTier() {
        return tier;
    }

    public ProviderType getProvider() {
        return provider;
    }

    public void setProvider(ProviderType provider) {
        this.provider = provider;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
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

    public Double getTemperature() {
        return temperature;
    }

    public void setTemperature(Double temperature) {
        this.temperature = temperature;
    }

    public Integer getMaxOutputTokens() {
        return maxOutputTokens;
    }

    public void setMaxOutputTokens(Integer maxOutputTokens) {
        this.maxOutputTokens = maxOutputTokens;
    }
}
