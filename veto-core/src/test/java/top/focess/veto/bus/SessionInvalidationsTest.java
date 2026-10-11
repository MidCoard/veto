package top.focess.veto.bus;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import top.focess.veto.contract.Delta;
import top.focess.veto.model.AgentInstanceRepository;
import top.focess.veto.model.SessionEntity;
import top.focess.veto.model.SessionRepository;
import top.focess.veto.vault.TestUsers;

class SessionInvalidationsTest {
    @Test
    void rollbackDoesNotPublishAndCommitDeliversOnlyResourceNames() throws Exception {
        DeltaBroker broker = new DeltaBroker();
        List<Delta> frames = new CopyOnWriteArrayList<>();
        broker.subscribeAll(
                frame -> {
                    if (frame instanceof Delta delta) frames.add(delta);
                });
        AgentInstanceRepository agents = mock(AgentInstanceRepository.class);
        var sessions = mock(SessionRepository.class);
        var entity = mock(SessionEntity.class);
        when(entity.getUserId()).thenReturn(TestUsers.OWNER);
        when(sessions.findById(anyString())).thenReturn(Optional.of(entity));
        SessionInvalidations service = new SessionInvalidations(broker, agents, sessions);
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
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
            while (frames.isEmpty() && System.nanoTime() < deadline) Thread.sleep(5);
            assertEquals(1, frames.size());
            assertEquals(session, frames.get(0).sessionId());
            assertInstanceOf(Delta.SessionInvalidated.class, frames.get(0).data());
            assertEquals(
                    List.of("agents", "execution"),
                    ((Delta.SessionInvalidated) frames.get(0).data()).resources());
        } finally {
            TransactionSynchronizationManager.clear();
            service.close();
        }
    }
}
