package top.focess.veto.plugin.secrets;

import static org.junit.jupiter.api.Assertions.*;
import static top.focess.veto.util.Nullness.requireNonNull;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import top.focess.veto.agent.capability.ProtectedWorkspaceReadCapabilityImpl;
import top.focess.veto.secret.references.SecretCandidateStore;

class SecretProtectionConfigurationTest {
    @Test
    void installsOneSharedStoreAndHostExpiryScheduler() {
        new ApplicationContextRunner()
                .withUserConfiguration(
                        requireNonNull(SecretProtectionConfiguration.class),
                        requireNonNull(ProtectedWorkspaceReadCapabilityImpl.class))
                .run(
                        context -> {
                            assertNull(context.getStartupFailure());
                            assertEquals(
                                    1, context.getBeansOfType(SecretCandidateStore.class).size());
                            assertNotNull(
                                    context.getBean(
                                            requireNonNull(
                                                    ProtectedWorkspaceReadCapabilityImpl.class)));
                            assertNotNull(
                                    context.getBean(
                                            requireNonNull(
                                                    SecretProtectionConfiguration.CandidateExpiry
                                                            .class)));
                        });
    }

    @Test
    void protectedReadCannotStartWithoutThePluginStore() {
        new ApplicationContextRunner()
                .withUserConfiguration(requireNonNull(ProtectedWorkspaceReadCapabilityImpl.class))
                .run(context -> assertNotNull(context.getStartupFailure()));
    }
}
