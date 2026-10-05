package top.focess.veto.model.tier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import top.focess.veto.api.llm.ProviderType;
import top.focess.veto.vault.TestUsers;

/**
 * Exercises the per-user, DB-backed {@link DefaultModelTierService}: profile lifecycle, per-field
 * binding configuration, active-profile switching, and fail-fast resolution. Mirrors the {@code
 * /modeltier} command's behavior end-to-end at the service layer.
 */
@DataJpaTest
@Import(DefaultModelTierService.class)
@SuppressWarnings("initialization.field.uninitialized")
class DefaultModelTierServiceTest {

    @Autowired @NonNull DefaultModelTierService service;
    @Autowired @NonNull ModelTierProfileRepository profileRepo;
    @Autowired @NonNull ModelTierBindingRepository bindingRepo;

    @MockitoBean @NonNull CredentialExistenceChecker credentialChecker;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void failedBatchRollsBackEarlierFieldsAndValidBatchPersistsTogether() {
        UUID userId = UUID.fromString("97a0b6da-dbbb-59f6-89a1-40353637c5d1");
        service.createProfile(userId, "default");
        try {
            service.setField(userId, "default", ModelTier.TOP, ModelTierField.MODEL, "original");
            Map<@NonNull ModelTierField, @NonNull String> fields = new LinkedHashMap<>();
            fields.put(ModelTierField.MODEL, "replacement");
            fields.put(ModelTierField.TEMPERATURE, "invalid");
            IllegalArgumentException failure =
                    assertThrows(
                            IllegalArgumentException.class,
                            () -> service.setFields(userId, "default", ModelTier.TOP, fields));
            assertTrue(String.valueOf(failure.getMessage()).contains("temp"));
            assertEquals("original", service.bindings(userId, "default").getFirst().getModel());
            fields.put(ModelTierField.TEMPERATURE, "0.5");
            service.setFields(userId, "default", ModelTier.TOP, fields);
            ModelTierBindingEntity binding = service.bindings(userId, "default").getFirst();
            assertEquals("replacement", binding.getModel());
            assertEquals(Double.valueOf(0.5), (Object) binding.getTemperature());
        } finally {
            service.deleteProfile(userId, "default");
        }
    }

    @Test
    void contextWindowPersistsAndValidatesOutputReservation() {
        service.createProfile(UUID.fromString("c7988511-0295-56d4-886c-f1fe06cdd468"), "default");
        service.setFields(
                UUID.fromString("c7988511-0295-56d4-886c-f1fe06cdd468"),
                "default",
                ModelTier.TOP,
                Map.of(
                        ModelTierField.PROVIDER,
                        "DEEPSEEK",
                        ModelTierField.MODEL,
                        "test",
                        ModelTierField.CREDENTIAL_KEY,
                        "key"));
        assertEquals(
                128000,
                service.resolve(
                                UUID.fromString("c7988511-0295-56d4-886c-f1fe06cdd468"),
                                ModelTier.TOP)
                        .contextWindowTokens());
        service.setField(
                UUID.fromString("c7988511-0295-56d4-886c-f1fe06cdd468"),
                "default",
                ModelTier.TOP,
                ModelTierField.CONTEXT_WINDOW_TOKENS,
                "64000");
        assertEquals(
                64000,
                service.resolve(
                                UUID.fromString("c7988511-0295-56d4-886c-f1fe06cdd468"),
                                ModelTier.TOP)
                        .contextWindowTokens());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        service.setField(
                                UUID.fromString("c7988511-0295-56d4-886c-f1fe06cdd468"),
                                "default",
                                ModelTier.TOP,
                                ModelTierField.CONTEXT_WINDOW_TOKENS,
                                "0"));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        service.setFields(
                                UUID.fromString("c7988511-0295-56d4-886c-f1fe06cdd468"),
                                "default",
                                ModelTier.TOP,
                                Map.of(
                                        ModelTierField.CONTEXT_WINDOW_TOKENS,
                                        "4000",
                                        ModelTierField.MAX_OUTPUT_TOKENS,
                                        "4096")));
    }

    @BeforeEach
    void assumeCredentialsExist() {
        // Happy-path tests set CREDENTIAL_KEY to arbitrary keys ("deepseek-default", "k", ...);
        // the service now validates existence, so default the checker to "exists" and let
        // individual tests override it to false to exercise the rejection path.
        when(credentialChecker.exists(any(UUID.class), anyString())).thenReturn(true);
    }

    @Test
    void resolveFailsWhenUserHasNoActiveProfile() {
        ModelTierConfigException e =
                assertThrows(
                        ModelTierConfigException.class,
                        () -> service.resolve(TestUsers.ALICE, ModelTier.TOP));
        assertTrue(String.valueOf(e.getMessage()).contains("No active model-tier profile"));
    }

    @Test
    void createProfileAutoActivatesFirstProfile() {
        service.createProfile(TestUsers.ALICE, "default");
        assertEquals("default", service.activeProfile(TestUsers.ALICE));
    }

    @Test
    void secondProfileIsCreatedInactive() {
        service.createProfile(TestUsers.ALICE, "default");
        service.createProfile(TestUsers.ALICE, "premium");
        assertEquals("default", service.activeProfile(TestUsers.ALICE));
        assertEquals(2, service.listProfiles(TestUsers.ALICE).size());
    }

    @Test
    void resolveReturnsFullyConfiguredBindingWithSamplingDefaults() {
        service.createProfile(TestUsers.ALICE, "default");
        service.setField(
                TestUsers.ALICE, "default", ModelTier.TOP, ModelTierField.PROVIDER, "deepseek");
        service.setField(
                TestUsers.ALICE, "default", ModelTier.TOP, ModelTierField.MODEL, "deepseek-chat");
        service.setField(
                TestUsers.ALICE,
                "default",
                ModelTier.TOP,
                ModelTierField.CREDENTIAL_KEY,
                "deepseek-default");

        ModelBinding resolved = service.resolve(TestUsers.ALICE, ModelTier.TOP);

        assertEquals(ProviderType.DEEPSEEK, resolved.provider());
        assertEquals("deepseek-chat", resolved.model());
        assertEquals("deepseek-default", resolved.credentialKey());
        assertEquals(0.7, resolved.temperature());
        assertEquals(4096, resolved.maxOutputTokens());
        assertNull(resolved.baseUrl());
    }

    @Test
    void setFieldPersistsPerFieldOverrides() {
        service.createProfile(TestUsers.ALICE, "default");
        service.setField(
                TestUsers.ALICE, "default", ModelTier.TOP, ModelTierField.PROVIDER, "anthropic");
        service.setField(
                TestUsers.ALICE, "default", ModelTier.TOP, ModelTierField.MODEL, "claude-opus");
        service.setField(
                TestUsers.ALICE,
                "default",
                ModelTier.TOP,
                ModelTierField.CREDENTIAL_KEY,
                "anthropic-default");
        service.setField(
                TestUsers.ALICE, "default", ModelTier.TOP, ModelTierField.TEMPERATURE, "0.5");
        service.setField(
                TestUsers.ALICE,
                "default",
                ModelTier.TOP,
                ModelTierField.MAX_OUTPUT_TOKENS,
                "8192");
        service.setField(
                TestUsers.ALICE,
                "default",
                ModelTier.TOP,
                ModelTierField.BASE_URL,
                "https://proxy.example/v1");

        ModelBinding resolved = service.resolve(TestUsers.ALICE, ModelTier.TOP);

        assertEquals(ProviderType.ANTHROPIC, resolved.provider());
        assertEquals("claude-opus", resolved.model());
        assertEquals("anthropic-default", resolved.credentialKey());
        assertEquals(0.5, resolved.temperature());
        assertEquals(8192, resolved.maxOutputTokens());
        assertEquals("https://proxy.example/v1", resolved.baseUrl());
    }

    @Test
    void blankBaseUrlClearsOverride() {
        service.createProfile(TestUsers.ALICE, "default");
        service.setField(
                TestUsers.ALICE, "default", ModelTier.TOP, ModelTierField.PROVIDER, "openai");
        service.setField(TestUsers.ALICE, "default", ModelTier.TOP, ModelTierField.MODEL, "gpt-4o");
        service.setField(
                TestUsers.ALICE, "default", ModelTier.TOP, ModelTierField.CREDENTIAL_KEY, "k");
        service.setField(
                TestUsers.ALICE,
                "default",
                ModelTier.TOP,
                ModelTierField.BASE_URL,
                "https://proxy/v1");
        service.setField(TestUsers.ALICE, "default", ModelTier.TOP, ModelTierField.BASE_URL, "   ");

        ModelBinding resolved = service.resolve(TestUsers.ALICE, ModelTier.TOP);
        assertNull(resolved.baseUrl());
    }

    @Test
    void switchingActiveProfileSwapsBinding() {
        service.createProfile(TestUsers.ALICE, "default");
        service.setField(
                TestUsers.ALICE, "default", ModelTier.TOP, ModelTierField.PROVIDER, "deepseek");
        service.setField(
                TestUsers.ALICE, "default", ModelTier.TOP, ModelTierField.MODEL, "deepseek-chat");
        service.setField(
                TestUsers.ALICE, "default", ModelTier.TOP, ModelTierField.CREDENTIAL_KEY, "dk");

        service.createProfile(TestUsers.ALICE, "premium");
        service.setField(
                TestUsers.ALICE, "premium", ModelTier.TOP, ModelTierField.PROVIDER, "anthropic");
        service.setField(
                TestUsers.ALICE, "premium", ModelTier.TOP, ModelTierField.MODEL, "claude-opus");
        service.setField(
                TestUsers.ALICE, "premium", ModelTier.TOP, ModelTierField.CREDENTIAL_KEY, "ak");
        service.activateProfile(TestUsers.ALICE, "premium");

        ModelBinding resolved = service.resolve(TestUsers.ALICE, ModelTier.TOP);
        assertEquals(ProviderType.ANTHROPIC, resolved.provider());
        assertEquals("claude-opus", resolved.model());
        assertEquals("premium", service.activeProfile(TestUsers.ALICE));

        // Only one profile is active at a time.
        long active =
                service.listProfiles(TestUsers.ALICE).stream()
                        .filter(ModelTierProfileEntity::isActive)
                        .count();
        assertEquals(1, active);
    }

    @Test
    void missingTierBindingFailFasts() {
        service.createProfile(TestUsers.ALICE, "default");
        service.setField(
                TestUsers.ALICE, "default", ModelTier.TOP, ModelTierField.PROVIDER, "deepseek");
        service.setField(
                TestUsers.ALICE, "default", ModelTier.TOP, ModelTierField.MODEL, "deepseek-chat");
        service.setField(
                TestUsers.ALICE, "default", ModelTier.TOP, ModelTierField.CREDENTIAL_KEY, "dk");

        // MID has no binding row in the profile.
        ModelTierConfigException e =
                assertThrows(
                        ModelTierConfigException.class,
                        () -> service.resolve(TestUsers.ALICE, ModelTier.MID));
        assertTrue(String.valueOf(e.getMessage()).contains("no binding for tier"));
    }

    @Test
    void incompleteBindingFailFasts() {
        service.createProfile(TestUsers.ALICE, "default");
        // provider set, model + credKey still unset.
        service.setField(
                TestUsers.ALICE, "default", ModelTier.TOP, ModelTierField.PROVIDER, "deepseek");

        ModelTierConfigException e =
                assertThrows(
                        ModelTierConfigException.class,
                        () -> service.resolve(TestUsers.ALICE, ModelTier.TOP));
        assertTrue(String.valueOf(e.getMessage()).contains("incomplete"));
    }

    @Test
    void createProfileRejectsDuplicateName() {
        service.createProfile(TestUsers.ALICE, "default");
        IllegalArgumentException e =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> service.createProfile(TestUsers.ALICE, "default"));
        assertTrue(String.valueOf(e.getMessage()).contains("already exists"));
    }

    @Test
    void setFieldRejectsUnknownProfile() {
        IllegalArgumentException e =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                service.setField(
                                        TestUsers.ALICE,
                                        "missing",
                                        ModelTier.TOP,
                                        ModelTierField.PROVIDER,
                                        "deepseek"));
        assertTrue(String.valueOf(e.getMessage()).contains("Profile not found"));
    }

    @Test
    void setFieldRejectsUnknownProvider() {
        service.createProfile(TestUsers.ALICE, "default");
        IllegalArgumentException e =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                service.setField(
                                        TestUsers.ALICE,
                                        "default",
                                        ModelTier.TOP,
                                        ModelTierField.PROVIDER,
                                        "nope"));
        assertTrue(String.valueOf(e.getMessage()).contains("Unknown provider"));
    }

    @Test
    void setFieldRejectsNonNumericTemperature() {
        service.createProfile(TestUsers.ALICE, "default");
        IllegalArgumentException e =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                service.setField(
                                        TestUsers.ALICE,
                                        "default",
                                        ModelTier.TOP,
                                        ModelTierField.TEMPERATURE,
                                        "warm"));
        assertTrue(String.valueOf(e.getMessage()).contains("Invalid temperature"));
    }

    @Test
    void setFieldRejectsMissingCredential() {
        when(credentialChecker.exists(any(UUID.class), anyString())).thenReturn(false);
        service.createProfile(TestUsers.ALICE, "default");
        IllegalArgumentException e =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                service.setField(
                                        TestUsers.ALICE,
                                        "default",
                                        ModelTier.TOP,
                                        ModelTierField.CREDENTIAL_KEY,
                                        "missing"));
        assertTrue(String.valueOf(e.getMessage()).contains("not found in the vault"));
    }

    @Test
    void profilesAreIsolatedPerUser() {
        service.createProfile(TestUsers.ALICE, "default");
        service.createProfile(TestUsers.BOB, "default");
        service.setField(
                TestUsers.ALICE, "default", ModelTier.TOP, ModelTierField.PROVIDER, "deepseek");
        service.setField(
                TestUsers.ALICE, "default", ModelTier.TOP, ModelTierField.MODEL, "deepseek-chat");
        service.setField(
                TestUsers.ALICE, "default", ModelTier.TOP, ModelTierField.CREDENTIAL_KEY, "ak");

        // Bob has his own active "default" profile, unconfigured -> incomplete.
        assertEquals("default", service.activeProfile(TestUsers.BOB));
        assertThrows(
                ModelTierConfigException.class,
                () -> service.resolve(TestUsers.BOB, ModelTier.TOP));
        assertEquals(1, service.listProfiles(TestUsers.ALICE).size());
        assertEquals(1, service.listProfiles(TestUsers.BOB).size());
    }

    @Test
    void deleteProfileRemovesProfileAndBindings() {
        service.createProfile(TestUsers.ALICE, "default");
        service.setField(
                TestUsers.ALICE, "default", ModelTier.TOP, ModelTierField.PROVIDER, "deepseek");
        service.setField(
                TestUsers.ALICE, "default", ModelTier.TOP, ModelTierField.MODEL, "deepseek-chat");
        service.setField(
                TestUsers.ALICE, "default", ModelTier.TOP, ModelTierField.CREDENTIAL_KEY, "dk");

        String profileId = service.profile(TestUsers.ALICE, "default").orElseThrow().getId();
        assertEquals(1, bindingRepo.findByProfileId(profileId).size());

        assertTrue(service.deleteProfile(TestUsers.ALICE, "default"));
        assertTrue(service.listProfiles(TestUsers.ALICE).isEmpty());
        assertEquals(0, bindingRepo.findByProfileId(profileId).size());
        assertFalse(service.deleteProfile(TestUsers.ALICE, "default"));
    }

    @Test
    void bindingsReturnsAllTiersForProfile() {
        service.createProfile(TestUsers.ALICE, "default");
        service.setField(
                TestUsers.ALICE, "default", ModelTier.TOP, ModelTierField.PROVIDER, "deepseek");
        service.setField(
                TestUsers.ALICE, "default", ModelTier.LOW, ModelTierField.PROVIDER, "openai");

        List<ModelTierBindingEntity> bindings = service.bindings(TestUsers.ALICE, "default");
        assertEquals(2, bindings.size());
    }
}
