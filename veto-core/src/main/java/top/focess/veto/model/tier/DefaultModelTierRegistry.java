package top.focess.veto.model.tier;

import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import top.focess.veto.llm.core.ProviderType;

/**
 * The default {@link ModelTierRegistry}: resolves a {@link ModelTier} against the active profile of
 * {@link ModelTierProperties}. This is the live resolution seam - patterns and agents call {@link
 * #resolve(ModelTier)} and receive the concrete binding the active configuration currently maps
 * that tier to.
 *
 * <p>Switching the active profile (changing {@code veto.model-tiers.active}) swaps the concrete
 * binding for every caller at the next resolution, without patterns or agents being aware of the
 * change.
 */
@Service
public class DefaultModelTierRegistry implements ModelTierRegistry {

    /** The binding returned when a tier is entirely unset - keeps resolution null-free. */
    private static final ModelBinding FALLBACK =
            new ModelBinding(ProviderType.DEEPSEEK, "deepseek-chat", "deepseek-default", 0.7, 4096);

    private final ModelTierProperties properties;

    public DefaultModelTierRegistry(ModelTierProperties properties) {
        this.properties = properties;
    }

    @Override
    public @NonNull ModelBinding resolve(@NonNull ModelTier tier) {
        ModelTierProperties.Profile profile = properties.activeProfile();
        if (profile == null) {
            return FALLBACK;
        }
        ModelTierProperties.Binding binding = profile.binding(tier);
        if (binding == null || binding.getProvider() == null || binding.getModel() == null) {
            // The tier is unset in the active profile; fall back to TOP if defined, else the
            // hardcoded default, so a partially-configured profile still resolves.
            ModelTierProperties.Binding top = profile.binding(ModelTier.TOP);
            if (top != null && top.getProvider() != null && top.getModel() != null) {
                return toModelBinding(top);
            }
            return FALLBACK;
        }
        return toModelBinding(binding);
    }

    @Override
    public @NonNull String activeProfile() {
        String active = properties.getActive();
        return active != null ? active : "default";
    }

    private static ModelBinding toModelBinding(ModelTierProperties.Binding b) {
        String credentialKey = b.getCredentialKey();
        if (credentialKey == null || credentialKey.isBlank()) {
            credentialKey = "deepseek-default";
        }
        return new ModelBinding(
                b.getProvider(),
                b.getModel(),
                credentialKey,
                b.getTemperature(),
                b.getMaxOutputTokens());
    }
}
