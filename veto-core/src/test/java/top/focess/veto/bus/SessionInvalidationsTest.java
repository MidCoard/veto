package top.focess.veto.bus;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import top.focess.veto.agent.tool.builtin.UserQuestionRegistry;
import top.focess.veto.model.AgentInstanceRepository;

class SessionInvalidationsTest {
    @Test
    void rollbackDoesNotPublishAndCommitDeliversOnlyResourceNames() throws Exception {
        DeltaBroker broker = new DeltaBroker();
        List<DeltaFrame> frames = new CopyOnWriteArrayList<>();
        broker.subscribeAll(frames::add);
        @NonNull AgentInstanceRepository agents = mock();
        SessionInvalidations service = new SessionInvalidations(broker, agents);
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
            assertEquals(DeltaFrame.Kind.SESSION_INVALIDATED, frames.get(0).kind());
            assertEquals(
                    "[\"agents\",\"execution\"]",
                    String.valueOf(frames.get(0).attrs().get("resources")));
            assertEquals("", frames.get(0).text());
        } finally {
            TransactionSynchronizationManager.clear();
            service.close();
        }
    }

    @Test
    void questionRegistrationAndExceptionalCleanupInvalidateButRejectedAnswersDoNot() {
        @NonNull SessionInvalidations changes = mock();
        UserQuestionRegistry questions = new UserQuestionRegistry();
        questions.attachInvalidations(changes);
        var pending = questions.register("agent", "call", List.of());
        verify(changes).agentChanged("agent", "interactions");
        clearInvocations(changes);
        assertFalse(questions.answer("agent", "missing", java.util.Map.of()));
        verifyNoInteractions(changes);
        pending.completeExceptionally(new IllegalStateException("interrupted"));
        assertTrue(questions.pendingFor("agent").isEmpty());
        verify(changes).agentChanged("agent", "interactions");
    }
}
