package top.focess.veto.model.tier;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import top.focess.veto.llm.core.ProviderType;

/**
 * The model-tier configuration ({@code veto.model-tiers}): a set of named <em>profiles</em>, each
 * mapping a {@link ModelTier} (TOP/MID/LOW/LOCAL) to a concrete {@link Binding} (provider + model +
 * credential-key + sampling defaults). Exactly one profile is {@link #active active} at a time;
 * switching it swaps the concrete model for every pattern and agent that references a tier.
 *
 * <p>Patterns and agents bind to a <em>tier name</em>, never a model id - they do not know which
 * concrete model they run on. The provider is set once per tier here (not inferred from a model id,
 * not typed into a pattern), which is what makes switching the whole configuration coherent:
 * changing the active profile changes provider, model, and credential-key together.
 *
 * <pre>
 * veto:
 *   model-tiers:
 *     active: default
 *     profiles:
 *       default:
 *         tiers:
 *           TOP:
 *             provider: DEEPSEEK
 *             model: deepseek-chat
 *             credential-key: deepseek-default   # vault SECURE_NOTE title (per-user)
 *             temperature: 0.7
 *             max-output-tokens: 4096
 *           MID: { ... }
 *           LOW: { ... }
 *           LOCAL: { ... }   # placeholder until a local-LLM provider impl exists
 *       premium:
 *         tiers:
 *           TOP: { provider: ANTHROPIC, model: claude-..., credential-key: anthropic-default, ... }
 * </pre>
 *
 * <p>Initialized with a working {@code default} profile so the engine resolves even before any YAML
 * is provided; YAML entries override or add profiles by name.
 */
@Configuration
@ConfigurationProperties("veto.model-tiers")
public class ModelTierProperties {

    /** The active profile name. Defaults to {@code "default"}. */
    private String active = "default";

    /** Named profiles, seeded with a working {@code default} profile. */
    private Map<String, Profile> profiles = new HashMap<>();

    public ModelTierProperties() {
        profiles.put("default", Profile.defaultProfile());
    }

    public String getActive() {
        return active;
    }

    public void setActive(String active) {
        this.active = active;
    }

    public Map<String, Profile> getProfiles() {
        return profiles;
    }

    public void setProfiles(Map<String, Profile> profiles) {
        this.profiles = profiles;
    }

    /**
     * The active {@link Profile}, falling back to the {@code default} profile when the named active
     * profile is absent.
     *
     * @return the active profile, or {@code null} if neither the active name nor {@code default}
     *     exists
     */
    public @Nullable Profile activeProfile() {
        Profile named = active == null ? null : profiles.get(active);
        if (named != null) {
            return named;
        }
        return profiles.get("default");
    }

    /** A named set of tier-to-binding mappings. */
    public static class Profile {

        private Map<ModelTier, Binding> tiers = new EnumMap<>(ModelTier.class);

        public Map<ModelTier, Binding> getTiers() {
            return tiers;
        }

        public void setTiers(Map<ModelTier, Binding> tiers) {
            this.tiers = tiers;
        }

        /** The binding for a tier within this profile, or {@code null} if unset. */
        public @Nullable Binding binding(@NonNull ModelTier tier) {
            return tiers.get(tier);
        }

        /** A working default profile: DEEPSEEK {@code deepseek-chat} at every tier. */
        static Profile defaultProfile() {
            Profile p = new Profile();
            p.tiers.put(ModelTier.TOP, binding(ProviderType.DEEPSEEK, "deepseek-chat", 0.7, 4096));
            p.tiers.put(ModelTier.MID, binding(ProviderType.DEEPSEEK, "deepseek-chat", 0.7, 4096));
            p.tiers.put(ModelTier.LOW, binding(ProviderType.DEEPSEEK, "deepseek-chat", 0.7, 4096));
            // LOCAL has no provider impl yet; mapped to the same model at low temp as a
            // placeholder.
            p.tiers.put(
                    ModelTier.LOCAL, binding(ProviderType.DEEPSEEK, "deepseek-chat", 0.1, 2048));
            return p;
        }

        private static Binding binding(
                ProviderType provider, String model, double temperature, int maxOutputTokens) {
            Binding b = new Binding();
            b.setProvider(provider);
            b.setModel(model);
            b.setCredentialKey("deepseek-default");
            b.setTemperature(temperature);
            b.setMaxOutputTokens(maxOutputTokens);
            return b;
        }
    }

    /** A concrete binding for one tier within a profile. Mutable for Spring binding. */
    public static class Binding {

        private ProviderType provider;
        private String model;
        private String credentialKey;
        private double temperature = 0.7;
        private int maxOutputTokens = 4096;

        public ProviderType getProvider() {
            return provider;
        }

        public void setProvider(ProviderType provider) {
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

        public double getTemperature() {
            return temperature;
        }

        public void setTemperature(double temperature) {
            this.temperature = temperature;
        }

        public int getMaxOutputTokens() {
            return maxOutputTokens;
        }

        public void setMaxOutputTokens(int maxOutputTokens) {
            this.maxOutputTokens = maxOutputTokens;
        }
    }
}
