package top.focess.veto.bus;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import top.focess.veto.contract.Frame;
import top.focess.veto.model.AgentInstanceRepository;
import top.focess.veto.model.SessionEntity;
import top.focess.veto.model.SessionRepository;
import top.focess.veto.vault.TestUsers;

class SessionActionsTest {
    @Test
    void rollbackDoesNotPublishAndCommitDeliversOnlyResourceNames() throws Exception {
        var broker = new DeltaBroker();
        var frames = new LinkedBlockingQueue<Frame.ScopedFrame>();
        var subscription = broker.subscribeAll(frames::add);
        AgentInstanceRepository agents = mock(AgentInstanceRepository.class);
        var sessions = mock(SessionRepository.class);
        var entity = mock(SessionEntity.class);
        when(entity.getUserId()).thenReturn(TestUsers.OWNER);
        when(sessions.findById(anyString())).thenReturn(Optional.of(entity));
        SessionActions service = new SessionActions(broker, agents, sessions);
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
            subscription.close();
            service.close();
        }
    }
}
