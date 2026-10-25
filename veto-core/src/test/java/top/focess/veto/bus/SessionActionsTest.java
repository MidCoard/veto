package top.focess.veto.bus;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import top.focess.veto.contract.Frame;
import top.focess.veto.model.AgentInstanceRepository;
import top.focess.veto.model.SessionEntity;
import top.focess.veto.model.SessionRepository;
import top.focess.veto.session.TestAgentSessions;
import top.focess.veto.session.TestConnection;
import top.focess.veto.vault.TestUsers;

class SessionActionsTest {
    @Test
    void closeWaitsForDeliveryCleanupAfterInterruptingTheWorker() throws Exception {
        var entered = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var worker = new AtomicReference<Thread>();
        var sessions = mock(SessionRepository.class);
        when(sessions.findById(anyString()))
                .thenAnswer(
                        invocation -> {
                            worker.set(Thread.currentThread());
                            entered.countDown();
                            try {
                                assertTrue(release.await(5, TimeUnit.SECONDS));
                            } catch (InterruptedException stopping) {
                                interrupted.countDown();
                                assertTrue(release.await(5, TimeUnit.SECONDS));
                            }
                            return Optional.empty();
                        });
        var service =
                new SessionActions(
                        TestAgentSessions.create(), mock(AgentInstanceRepository.class), sessions);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            service.changed(UUID.randomUUID(), "agents");
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            var closing = workers.submit(service::close);
            try {
                assertTrue(interrupted.await(2, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> closing.get(100, TimeUnit.MILLISECONDS));
            } finally {
                release.countDown();
            }
            closing.get(2, TimeUnit.SECONDS);
            var running = worker.get();
            assertNotNull(running);
            assertFalse(running.isAlive());
        } finally {
            release.countDown();
            service.close();
        }
    }

    @Test
    void rollbackDoesNotPublishAndCommitDeliversOnlyResourceNames() throws Exception {
        var runtime = TestAgentSessions.create();
        var frames = new LinkedBlockingQueue<Frame.ScopedFrame>();
        var connection = new TestConnection(TestUsers.OWNER, frames::add);
        runtime.connectBrowser(connection.connection());
        AgentInstanceRepository agents = mock(AgentInstanceRepository.class);
        var sessions = mock(SessionRepository.class);
        var entity = mock(SessionEntity.class);
        when(entity.getUserId()).thenReturn(TestUsers.OWNER);
        when(sessions.findById(anyString())).thenReturn(Optional.of(entity));
        SessionActions service = new SessionActions(runtime, agents, sessions);
        try {
            TransactionSynchronizationManager.initSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(true);
            service.changed(UUID.randomUUID(), "groups");
            assertTrue(frames.isEmpty());
            TransactionSynchronizationManager.clear();
            TransactionSynchronizationManager.initSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(true);
            UUID session = UUID.randomUUID();
            service.changed(session, "agents", "execution");
            for (var callback : TransactionSynchronizationManager.getSynchronizations())
                callback.afterCommit();
            var frame = frames.poll(2, TimeUnit.SECONDS);
            if (frame == null) throw new AssertionError("Committed action was not delivered");
            var changed = assertInstanceOf(Frame.Action.class, frame);
            assertEquals(session.toString(), changed.scope().session());
            assertEquals(List.of("agents", "execution"), changed.resources());
        } finally {
            TransactionSynchronizationManager.clear();
            runtime.disconnect(connection.connection());
            service.close();
        }
    }
}
