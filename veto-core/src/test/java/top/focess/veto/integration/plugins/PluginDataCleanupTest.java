package top.focess.veto.integration.plugins;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import top.focess.veto.api.plugin.PluginContext;
import top.focess.veto.api.plugin.contract.DataLifecycle;
import top.focess.veto.api.plugin.contract.StandardContributionPoints;
import top.focess.veto.api.plugin.contribution.Contribution;
import top.focess.veto.api.plugin.contribution.ContributionPoint;
import top.focess.veto.plugin.runtime.PluginLifecycle;
import top.focess.veto.vault.UserEntity;
import top.focess.veto.vault.UserRegistry;

class PluginDataCleanupTest {
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void completionReceivesTransactionOutcomeAndReleasesContributorClaim(boolean committed)
            throws Exception {
        try (var manager = PluginTestSupport.manager()) {
            var registration = manager.registrations().getFirst();
            var plugin = registration.plugin();
            var differentActivation = mock(PluginLifecycle.class);
            when(differentActivation.identity()).thenReturn(plugin.identity());
            assertThrows(
                    IllegalStateException.class,
                    () -> manager.beginDataCleanup(differentActivation));
            assertThrows(
                    IllegalStateException.class,
                    () -> manager.endDataCleanup(plugin.identity().id()));
            Map<@NonNull ContributionPoint<?>, @NonNull Consumer<@NonNull Contribution<?>>>
                    handlers =
                            ReflectionTestUtils.invokeMethod(
                                    manager, "contributionHandlers", plugin, registration.points());
            if (handlers == null) throw new AssertionError("Registration handlers must exist");
            var prepared = new AtomicInteger();
            List<@NonNull Boolean> outcomes = new ArrayList<>();
            var participant =
                    new DataLifecycle() {
                        @Override
                        public @NonNull Completion prepareOwnerDeletion(
                                @NonNull String owner, @NonNull String userId) {
                            throw new AssertionError("Expected session deletion");
                        }

                        @Override
                        public @NonNull Completion prepareSessionDeletion(
                                @NonNull String owner,
                                @NonNull String userId,
                                @NonNull String sessionId) {
                            assertEquals("login", owner);
                            assertEquals("immutable-user", userId);
                            assertEquals("session", sessionId);
                            prepared.incrementAndGet();
                            return outcomes::add;
                        }
                    };
            var context =
                    new PluginContext(
                            plugin.identity(), () -> {}, plugin::state, Map.of(), handlers);
            context.register(
                    StandardContributionPoints.DATA_LIFECYCLE, "cleanup-probe", participant);
            var users = mock(UserRegistry.class);
            var user = mock(UserEntity.class);
            when(user.storageIdentity()).thenReturn("immutable-user");
            when(users.findByUsername("login")).thenReturn(Optional.of(user));
            var cleanup = new PluginDataCleanup(manager);
            cleanup.attachUsers(users);
            assertThrows(
                    IllegalStateException.class,
                    () -> cleanup.beforeSessionDeleted("login", "session"));
            assertEquals(0, prepared.get());
            TransactionSynchronizationManager.initSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(true);
            try {
                cleanup.beforeSessionDeleted("login", "session");
                assertEquals(1, prepared.get());
                assertTrue(outcomes.isEmpty());
                for (var synchronization :
                        TransactionSynchronizationManager.getSynchronizations()) {
                    synchronization.afterCompletion(
                            committed
                                    ? TransactionSynchronization.STATUS_COMMITTED
                                    : TransactionSynchronization.STATUS_ROLLED_BACK);
                }
                assertEquals(List.of(committed), outcomes);
                assertThrows(
                        IllegalStateException.class,
                        () -> manager.endDataCleanup(plugin.identity().id()));
            } finally {
                TransactionSynchronizationManager.clear();
            }
        }
    }
}
