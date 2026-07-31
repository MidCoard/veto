package top.focess.veto.model.tier;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import top.focess.veto.llm.core.ProviderType;

class DefaultModelTierRegistryTest {

    @Test
    void resolvesSeededDefaultProfile() {
        ModelTierProperties properties = new ModelTierProperties();
        DefaultModelTierRegistry registry = new DefaultModelTierRegistry(properties);

        ModelBinding top = registry.resolve(ModelTier.TOP);

        assertEquals(ProviderType.DEEPSEEK, top.provider());
        assertEquals("deepseek-chat", top.model());
        assertEquals("deepseek-default", top.credentialKey());
        assertEquals("default", registry.activeProfile());
    }

    @Test
    void switchingActiveProfileSwapsBinding() {
        ModelTierProperties properties = new ModelTierProperties();
        ModelTierProperties.Profile premium = new ModelTierProperties.Profile();
        ModelTierProperties.Binding top = new ModelTierProperties.Binding();
        top.setProvider(ProviderType.ANTHROPIC);
        top.setModel("claude-opus");
        top.setCredentialKey("anthropic-default");
        top.setTemperature(0.5);
        top.setMaxOutputTokens(8192);
        premium.getTiers().put(ModelTier.TOP, top);
        properties.getProfiles().put("premium", premium);
        properties.setActive("premium");

        DefaultModelTierRegistry registry = new DefaultModelTierRegistry(properties);

        ModelBinding resolved = registry.resolve(ModelTier.TOP);

        assertEquals(ProviderType.ANTHROPIC, resolved.provider());
        assertEquals("claude-opus", resolved.model());
        assertEquals("anthropic-default", resolved.credentialKey());
        assertEquals(0.5, resolved.temperature());
        assertEquals(8192, resolved.maxOutputTokens());
        assertEquals("premium", registry.activeProfile());
    }

    @Test
    void unsetTierFallsBackToTop() {
        ModelTierProperties properties = new ModelTierProperties();
        ModelTierProperties.Profile profile = new ModelTierProperties.Profile();
        ModelTierProperties.Binding top = new ModelTierProperties.Binding();
        top.setProvider(ProviderType.OPENAI);
        top.setModel("gpt-4o");
        top.setCredentialKey("openai-default");
        top.setTemperature(0.7);
        top.setMaxOutputTokens(4096);
        profile.getTiers().put(ModelTier.TOP, top);
        properties.getProfiles().put("default", profile);

        DefaultModelTierRegistry registry = new DefaultModelTierRegistry(properties);

        // MID is unset in the profile -> falls back to the TOP binding.
        ModelBinding mid = registry.resolve(ModelTier.MID);

        assertEquals(ProviderType.OPENAI, mid.provider());
        assertEquals("gpt-4o", mid.model());
    }

    @Test
    void missingActiveProfileFallsBackToDefault() {
        ModelTierProperties properties = new ModelTierProperties();
        properties.setActive("does-not-exist");

        DefaultModelTierRegistry registry = new DefaultModelTierRegistry(properties);

        // The named active profile is absent, so the seeded "default" profile is used.
        ModelBinding top = registry.resolve(ModelTier.TOP);

        assertEquals(ProviderType.DEEPSEEK, top.provider());
        assertEquals("deepseek-chat", top.model());
    }
}
