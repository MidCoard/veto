package top.focess.veto.model.tier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import top.focess.veto.llm.core.ProviderType;

/**
 * Exercises the per-user, DB-backed {@link DefaultModelTierService}: profile lifecycle, per-field
 * binding configuration, active-profile switching, and fail-fast resolution. Mirrors the {@code
 * /modeltier} command's behavior end-to-end at the service layer.
 */
@DataJpaTest
@Import(DefaultModelTierService.class)
class DefaultModelTierServiceTest {

    @Autowired DefaultModelTierService service;
    @Autowired ModelTierProfileRepository profileRepo;
    @Autowired ModelTierBindingRepository bindingRepo;

    @MockBean CredentialExistenceChecker credentialChecker;

    @BeforeEach
    void assumeCredentialsExist() {
        // Happy-path tests set CREDENTIAL_KEY to arbitrary keys ("deepseek-default", "k", ...);
        // the service now validates existence, so default the checker to "exists" and let
        // individual tests override it to false to exercise the rejection path.
        when(credentialChecker.exists(anyString(), anyString())).thenReturn(true);
    }

    @Test
    void resolveFailsWhenUserHasNoActiveProfile() {
        ModelTierConfigException e =
                assertThrows(
                        ModelTierConfigException.class,
                        () -> service.resolve("alice", ModelTier.TOP));
        assertTrue(e.getMessage().contains("No active model-tier profile"));
    }

    @Test
    void createProfileAutoActivatesFirstProfile() {
        service.createProfile("alice", "default");
        assertEquals("default", service.activeProfile("alice"));
    }

    @Test
    void secondProfileIsCreatedInactive() {
        service.createProfile("alice", "default");
        service.createProfile("alice", "premium");
        assertEquals("default", service.activeProfile("alice"));
        assertEquals(2, service.listProfiles("alice").size());
    }

    @Test
    void resolveReturnsFullyConfiguredBindingWithSamplingDefaults() {
        service.createProfile("alice", "default");
        service.setField("alice", "default", ModelTier.TOP, ModelTierField.PROVIDER, "deepseek");
        service.setField("alice", "default", ModelTier.TOP, ModelTierField.MODEL, "deepseek-chat");
        service.setField(
                "alice",
                "default",
                ModelTier.TOP,
                ModelTierField.CREDENTIAL_KEY,
                "deepseek-default");

        ModelBinding resolved = service.resolve("alice", ModelTier.TOP);

        assertEquals(ProviderType.DEEPSEEK, resolved.provider());
        assertEquals("deepseek-chat", resolved.model());
        assertEquals("deepseek-default", resolved.credentialKey());
        assertEquals(0.7, resolved.temperature());
        assertEquals(4096, resolved.maxOutputTokens());
        assertNull(resolved.baseUrl());
    }

    @Test
    void setFieldPersistsPerFieldOverrides() {
        service.createProfile("alice", "default");
        service.setField("alice", "default", ModelTier.TOP, ModelTierField.PROVIDER, "anthropic");
        service.setField("alice", "default", ModelTier.TOP, ModelTierField.MODEL, "claude-opus");
        service.setField(
                "alice",
                "default",
                ModelTier.TOP,
                ModelTierField.CREDENTIAL_KEY,
                "anthropic-default");
        service.setField("alice", "default", ModelTier.TOP, ModelTierField.TEMPERATURE, "0.5");
        service.setField(
                "alice", "default", ModelTier.TOP, ModelTierField.MAX_OUTPUT_TOKENS, "8192");
        service.setField(
                "alice",
                "default",
                ModelTier.TOP,
                ModelTierField.BASE_URL,
                "https://proxy.example/v1");

        ModelBinding resolved = service.resolve("alice", ModelTier.TOP);

        assertEquals(ProviderType.ANTHROPIC, resolved.provider());
        assertEquals("claude-opus", resolved.model());
        assertEquals("anthropic-default", resolved.credentialKey());
        assertEquals(0.5, resolved.temperature());
        assertEquals(8192, resolved.maxOutputTokens());
        assertEquals("https://proxy.example/v1", resolved.baseUrl());
    }

    @Test
    void blankBaseUrlClearsOverride() {
        service.createProfile("alice", "default");
        service.setField("alice", "default", ModelTier.TOP, ModelTierField.PROVIDER, "openai");
        service.setField("alice", "default", ModelTier.TOP, ModelTierField.MODEL, "gpt-4o");
        service.setField("alice", "default", ModelTier.TOP, ModelTierField.CREDENTIAL_KEY, "k");
        service.setField(
                "alice", "default", ModelTier.TOP, ModelTierField.BASE_URL, "https://proxy/v1");
        service.setField("alice", "default", ModelTier.TOP, ModelTierField.BASE_URL, "   ");

        ModelBinding resolved = service.resolve("alice", ModelTier.TOP);
        assertNull(resolved.baseUrl());
    }

    @Test
    void switchingActiveProfileSwapsBinding() {
        service.createProfile("alice", "default");
        service.setField("alice", "default", ModelTier.TOP, ModelTierField.PROVIDER, "deepseek");
        service.setField("alice", "default", ModelTier.TOP, ModelTierField.MODEL, "deepseek-chat");
        service.setField("alice", "default", ModelTier.TOP, ModelTierField.CREDENTIAL_KEY, "dk");

        service.createProfile("alice", "premium");
        service.setField("alice", "premium", ModelTier.TOP, ModelTierField.PROVIDER, "anthropic");
        service.setField("alice", "premium", ModelTier.TOP, ModelTierField.MODEL, "claude-opus");
        service.setField("alice", "premium", ModelTier.TOP, ModelTierField.CREDENTIAL_KEY, "ak");
        service.activateProfile("alice", "premium");

        ModelBinding resolved = service.resolve("alice", ModelTier.TOP);
        assertEquals(ProviderType.ANTHROPIC, resolved.provider());
        assertEquals("claude-opus", resolved.model());
        assertEquals("premium", service.activeProfile("alice"));

        // Only one profile is active at a time.
        long active =
                service.listProfiles("alice").stream()
                        .filter(ModelTierProfileEntity::isActive)
                        .count();
        assertEquals(1, active);
    }

    @Test
    void missingTierBindingFailFasts() {
        service.createProfile("alice", "default");
        service.setField("alice", "default", ModelTier.TOP, ModelTierField.PROVIDER, "deepseek");
        service.setField("alice", "default", ModelTier.TOP, ModelTierField.MODEL, "deepseek-chat");
        service.setField("alice", "default", ModelTier.TOP, ModelTierField.CREDENTIAL_KEY, "dk");

        // MID has no binding row in the profile.
        ModelTierConfigException e =
                assertThrows(
                        ModelTierConfigException.class,
                        () -> service.resolve("alice", ModelTier.MID));
        assertTrue(e.getMessage().contains("no binding for tier"));
    }

    @Test
    void incompleteBindingFailFasts() {
        service.createProfile("alice", "default");
        // provider set, model + credKey still unset.
        service.setField("alice", "default", ModelTier.TOP, ModelTierField.PROVIDER, "deepseek");

        ModelTierConfigException e =
                assertThrows(
                        ModelTierConfigException.class,
                        () -> service.resolve("alice", ModelTier.TOP));
        assertTrue(e.getMessage().contains("incomplete"));
    }

    @Test
    void createProfileRejectsDuplicateName() {
        service.createProfile("alice", "default");
        IllegalArgumentException e =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> service.createProfile("alice", "default"));
        assertTrue(e.getMessage().contains("already exists"));
    }

    @Test
    void setFieldRejectsUnknownProfile() {
        IllegalArgumentException e =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                service.setField(
                                        "alice",
                                        "missing",
                                        ModelTier.TOP,
                                        ModelTierField.PROVIDER,
                                        "deepseek"));
        assertTrue(e.getMessage().contains("Profile not found"));
    }

    @Test
    void setFieldRejectsUnknownProvider() {
        service.createProfile("alice", "default");
        IllegalArgumentException e =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                service.setField(
                                        "alice",
                                        "default",
                                        ModelTier.TOP,
                                        ModelTierField.PROVIDER,
                                        "nope"));
        assertTrue(e.getMessage().contains("Unknown provider"));
    }

    @Test
    void setFieldRejectsNonNumericTemperature() {
        service.createProfile("alice", "default");
        IllegalArgumentException e =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                service.setField(
                                        "alice",
                                        "default",
                                        ModelTier.TOP,
                                        ModelTierField.TEMPERATURE,
                                        "warm"));
        assertTrue(e.getMessage().contains("Invalid temperature"));
    }

    @Test
    void setFieldRejectsMissingCredential() {
        when(credentialChecker.exists(anyString(), anyString())).thenReturn(false);
        service.createProfile("alice", "default");
        IllegalArgumentException e =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                service.setField(
                                        "alice",
                                        "default",
                                        ModelTier.TOP,
                                        ModelTierField.CREDENTIAL_KEY,
                                        "missing"));
        assertTrue(e.getMessage().contains("not found in the vault"));
    }

    @Test
    void profilesAreIsolatedPerUser() {
        service.createProfile("alice", "default");
        service.createProfile("bob", "default");
        service.setField("alice", "default", ModelTier.TOP, ModelTierField.PROVIDER, "deepseek");
        service.setField("alice", "default", ModelTier.TOP, ModelTierField.MODEL, "deepseek-chat");
        service.setField("alice", "default", ModelTier.TOP, ModelTierField.CREDENTIAL_KEY, "ak");

        // Bob has his own active "default" profile, unconfigured -> incomplete.
        assertEquals("default", service.activeProfile("bob"));
        assertThrows(ModelTierConfigException.class, () -> service.resolve("bob", ModelTier.TOP));
        assertEquals(1, service.listProfiles("alice").size());
        assertEquals(1, service.listProfiles("bob").size());
    }

    @Test
    void deleteProfileRemovesProfileAndBindings() {
        service.createProfile("alice", "default");
        service.setField("alice", "default", ModelTier.TOP, ModelTierField.PROVIDER, "deepseek");
        service.setField("alice", "default", ModelTier.TOP, ModelTierField.MODEL, "deepseek-chat");
        service.setField("alice", "default", ModelTier.TOP, ModelTierField.CREDENTIAL_KEY, "dk");

        String profileId = service.profile("alice", "default").orElseThrow().getId();
        assertEquals(1, bindingRepo.findByProfileId(profileId).size());

        assertTrue(service.deleteProfile("alice", "default"));
        assertTrue(service.listProfiles("alice").isEmpty());
        assertEquals(0, bindingRepo.findByProfileId(profileId).size());
        assertFalse(service.deleteProfile("alice", "default"));
    }

    @Test
    void bindingsReturnsAllTiersForProfile() {
        service.createProfile("alice", "default");
        service.setField("alice", "default", ModelTier.TOP, ModelTierField.PROVIDER, "deepseek");
        service.setField("alice", "default", ModelTier.LOW, ModelTierField.PROVIDER, "openai");

        List<ModelTierBindingEntity> bindings = service.bindings("alice", "default");
        assertEquals(2, bindings.size());
    }
}
