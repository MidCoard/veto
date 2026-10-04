package top.focess.veto.integration.plugins;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import top.focess.veto.api.plugin.PluginContext;
import top.focess.veto.api.plugin.PluginState;
import top.focess.veto.api.plugin.contract.DataLifecycle;
import top.focess.veto.api.plugin.contract.StandardContributionPoints;
import top.focess.veto.api.plugin.contribution.Contribution;
import top.focess.veto.api.plugin.contribution.ContributionPoint;
import top.focess.veto.plugin.runtime.ManagedPlugin;
import top.focess.veto.vault.UserEntity;
import top.focess.veto.vault.UserRegistry;

class PluginDataCleanupTest {
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @SuppressWarnings(
            "try") // Explicit close exercises shutdown; the fixture still owns final cleanup.
    void completionReceivesTransactionOutcomeAndReleasesContributorClaim(boolean committed)
            throws Exception {
        try (var manager = PluginTestSupport.manager()) {
            var plugin = manager.registry().plugins().getFirst();
            var differentActivation = mock(ManagedPlugin.class);
            when(differentActivation.identity()).thenReturn(plugin.identity());
            assertThrows(
                    IllegalStateException.class,
                    () -> manager.beginDataCleanup(differentActivation));
            assertThrows(
                    IllegalStateException.class,
                    () -> manager.endDataCleanup(plugin.identity().id()));
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
                            return committedOutcome -> {
                                assertEquals(PluginState.ACTIVE, plugin.state());
                                outcomes.add(committedOutcome);
                            };
                        }
                    };
            var context = registrationContext(manager, plugin);
            context.register(
                    StandardContributionPoints.DATA_LIFECYCLE, "cleanup-probe", participant);
            var users = mock(UserRegistry.class);
            var user = mock(UserEntity.class);
            when(user.storageIdentity()).thenReturn("immutable-user");
            when(users.findByUsername("login")).thenReturn(Optional.of(user));
            var cleanup = new PluginDataCleanup(manager, users);
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
                assertThrows(IllegalStateException.class, manager::close);
                var synchronizations = TransactionSynchronizationManager.getSynchronizations();
                try (var shutdown = Executors.newSingleThreadExecutor()) {
                    var started = new CountDownLatch(1);
                    var closing =
                            shutdown.submit(
                                    () -> {
                                        started.countDown();
                                        manager.close();
                                    });
                    boolean delivered = false;
                    try {
                        assertTrue(started.await(5, TimeUnit.SECONDS));
                        awaitClosing(manager);
                        assertFalse(closing.isDone());
                        assertEquals(PluginState.ACTIVE, plugin.state());
                        assertThrows(
                                IllegalStateException.class,
                                () -> manager.beginDataCleanup(plugin));
                        delivered = true;
                        for (var synchronization : synchronizations) {
                            synchronization.afterCompletion(
                                    committed
                                            ? TransactionSynchronization.STATUS_COMMITTED
                                            : TransactionSynchronization.STATUS_ROLLED_BACK);
                        }
                        closing.get(5, TimeUnit.SECONDS);
                        assertEquals(PluginState.CLOSED, plugin.state());
                    } finally {
                        if (!delivered)
                            for (var synchronization : synchronizations)
                                synchronization.afterCompletion(
                                        TransactionSynchronization.STATUS_ROLLED_BACK);
                    }
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

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void preparationErrorsReleaseClaimsAndFatalSignalsKeepTheirIdentity(boolean fatal)
            throws Exception {
        try (var manager = PluginTestSupport.manager()) {
            Error failure =
                    fatal
                            ? new VirtualMachineError("private participant failure") {}
                            : new AssertionError("private participant failure");
            register(
                    manager,
                    new DataLifecycle() {
                        @Override
                        public @NonNull Completion prepareOwnerDeletion(
                                @NonNull String owner, @NonNull String userId) {
                            throw failure;
                        }

                        @Override
                        public @NonNull Completion prepareSessionDeletion(
                                @NonNull String owner,
                                @NonNull String userId,
                                @NonNull String sessionId) {
                            throw failure;
                        }
                    });
            var plugin = manager.registry().plugins().getFirst();
            var cleanup = cleanup(manager);
            TransactionSynchronizationManager.initSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(true);
            try {
                if (fatal) {
                    assertSame(
                            failure,
                            assertThrows(
                                    VirtualMachineError.class,
                                    () -> cleanup.beforeSessionDeleted("login", "session")));
                } else {
                    var unavailable =
                            assertThrows(
                                    IllegalStateException.class,
                                    () -> cleanup.beforeSessionDeleted("login", "session"));
                    assertEquals("Plugin data cleanup is unavailable", unavailable.getMessage());
                    var cause = unavailable.getCause();
                    if (cause == null)
                        throw new AssertionError("Preparation failure cause was lost");
                    assertSame(failure, cause);
                }
                assertTrue(TransactionSynchronizationManager.getSynchronizations().isEmpty());
                assertThrows(
                        IllegalStateException.class,
                        () -> manager.endDataCleanup(plugin.identity().id()));
                assertTrue(plugin.execute(() -> true));
            } finally {
                TransactionSynchronizationManager.clear();
            }
        }
    }

    private static void awaitClosing(@NonNull PluginManager manager) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            synchronized (manager) {
                if (Boolean.TRUE.equals(ReflectionTestUtils.getField(manager, "closing"))) return;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
        throw new AssertionError("Shutdown did not begin draining deletion claims");
    }

    private static void register(
            @NonNull PluginManager manager, @NonNull DataLifecycle participant) {
        var plugin = manager.registry().plugins().getFirst();
        var context = registrationContext(manager, plugin);
        context.register(StandardContributionPoints.DATA_LIFECYCLE, "cleanup-probe", participant);
    }

    private static @NonNull PluginContext registrationContext(
            @NonNull PluginManager manager, @NonNull ManagedPlugin plugin) {
        // These tests deliberately mutate live registration staging to exercise cleanup claims.
        var staged = ReflectionTestUtils.getField(manager, "stagedRegistrations");
        if (!(staged instanceof List<?> registrations)) throw new AssertionError("Missing staging");
        for (var registration : registrations) {
            if (registration == null) throw new AssertionError("Missing registration");
            var owner = ReflectionTestUtils.invokeMethod(registration, "plugin");
            if (owner != plugin) continue;
            var points = ReflectionTestUtils.invokeMethod(registration, "points");
            if (points == null) throw new AssertionError("Missing registration points");
            Map<@NonNull ContributionPoint<?>, @NonNull Consumer<@NonNull Contribution<?>>>
                    handlers =
                            ReflectionTestUtils.invokeMethod(
                                    manager, "contributionHandlers", plugin, points);
            if (handlers == null) throw new AssertionError("Registration handlers must exist");
            return new PluginContext(
                    plugin.identity(), () -> {}, plugin::state, Map.of(), handlers);
        }
        throw new AssertionError("Registration is missing");
    }

    private static @NonNull PluginDataCleanup cleanup(@NonNull PluginManager manager) {
        var users = mock(UserRegistry.class);
        var user = mock(UserEntity.class);
        when(user.storageIdentity()).thenReturn("immutable-user");
        when(users.findByUsername("login")).thenReturn(Optional.of(user));
        return new PluginDataCleanup(manager, users);
    }
}
