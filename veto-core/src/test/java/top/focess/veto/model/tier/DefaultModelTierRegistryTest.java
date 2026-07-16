package top.focess.veto.model.tier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import top.focess.veto.model.AgentPatternEntity;
import top.focess.veto.model.AgentPatternRepository;
import top.focess.veto.vault.CredentialVault;

class DefaultModelTierRegistryTest {

    @Test
    void resolveModelTiersCorrectly() {
        DefaultModelTierRegistry registry = new DefaultModelTierRegistry();
        AgentPatternRepository patternRepo = mock(AgentPatternRepository.class);
        CredentialVault vault = mock(CredentialVault.class);

        ReflectionTestUtils.setField(registry, "patternRepo", patternRepo);
        ReflectionTestUtils.setField(registry, "vault", vault);

        String patternName = "test-pattern";
        String user = "test-user";
        when(vault.getCurrentUser()).thenReturn(user);

        // Case 1: All model tiers configured
        AgentPatternEntity p1 =
                new AgentPatternEntity(
                        patternName,
                        "gemini",
                        "gemini-model",
                        "cred-key",
                        user,
                        "gemini-top",
                        "gemini-mid",
                        "gemini-low");
        when(patternRepo.findByNameAndOwner(patternName, user)).thenReturn(Optional.of(p1));

        ModelBinding topResolved = registry.resolve(patternName, ModelTier.TOP);
        ModelBinding midResolved = registry.resolve(patternName, ModelTier.MID);
        ModelBinding lowResolved = registry.resolve(patternName, ModelTier.LOW);

        assertEquals("gemini-top", topResolved.modelId());
        assertEquals("gemini-mid", midResolved.modelId());
        assertEquals("gemini-low", lowResolved.modelId());

        // Case 2: midModel and lowModel are null, should default to topModel
        AgentPatternEntity p2 =
                new AgentPatternEntity(
                        patternName,
                        "gemini",
                        "gemini-model",
                        "cred-key",
                        user,
                        "gemini-top",
                        null,
                        null);
        when(patternRepo.findByNameAndOwner(patternName, user)).thenReturn(Optional.of(p2));

        ModelBinding midResolvedDefault = registry.resolve(patternName, ModelTier.MID);
        ModelBinding lowResolvedDefault = registry.resolve(patternName, ModelTier.LOW);

        assertEquals("gemini-top", midResolvedDefault.modelId());
        assertEquals("gemini-top", lowResolvedDefault.modelId());
    }
}
