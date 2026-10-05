package top.focess.veto.memory;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import top.focess.veto.agent.TurnRecord;
import top.focess.veto.api.llm.ToolCall;
import top.focess.veto.bus.DeltaBroker;

/**
 * Verifies {@link TurnLogService} persists turns to the raw-turn log ({@link TurnRecordRepository})
 * only - turn persistence is session state; nothing feeds LTM (long-term memory is agent-written
 * only, via {@code write_memory}).
 */
class TurnLogServiceTest {

    @Test
    void publishesOnlyAfterCommitAndNotOnRollback() {
        TurnRecordRepository repo = mock(TurnRecordRepository.class);
        DeltaBroker broker = mock(DeltaBroker.class);
        var service = new TurnLogService(repo, new ObjectMapper(), broker);
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            service.log(
                    TurnRecord.userPrompt(1, "hello"),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    "agent");
            verifyNoInteractions(broker);
            for (var callback : TransactionSynchronizationManager.getSynchronizations())
                callback.afterCommit();
            verify(broker).publish(any());
        } finally {
            TransactionSynchronizationManager.clear();
        }
        reset(broker);
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            service.logRequired(
                    TurnRecord.userPrompt(2, "notice"),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    "agent");
            for (var callback : TransactionSynchronizationManager.getSynchronizations())
                callback.afterCompletion(1);
            verifyNoInteractions(broker);
        } finally {
            TransactionSynchronizationManager.clear();
        }
    }

    @Test
    void failedWritesAndUnchangedMetadataDoNotNotify() {
        TurnRecordRepository repo = mock(TurnRecordRepository.class);
        DeltaBroker broker = mock(DeltaBroker.class);
        var service = new TurnLogService(repo, new ObjectMapper(), broker);
        when(repo.save(any())).thenThrow(new IllegalStateException("offline"));
        service.log(
                TurnRecord.userPrompt(1, "hello"), UUID.randomUUID(), UUID.randomUUID(), "agent");
        service.updateMetadata(
                TurnRecord.userPrompt(1, "hello"), UUID.randomUUID(), UUID.randomUUID(), "agent");
        verifyNoInteractions(broker);
        when(repo.updateRecordMetadata(
                        anyString(), any(), anyString(), anyInt(), anyString(), anyString()))
                .thenReturn(1);
        service.updateMetadata(
                TurnRecord.userPrompt(1, "hello"), UUID.randomUUID(), UUID.randomUUID(), "agent");
        verify(broker).publish(any());
    }

    @Test
    void requiredLoggingPropagatesStorageFailure() {
        TurnRecordRepository repo = mock(TurnRecordRepository.class);
        var service = new TurnLogService(repo, new ObjectMapper(), new DeltaBroker());
        when(repo.save(any())).thenThrow(new IllegalStateException("database unavailable"));
        assertThrows(
                IllegalStateException.class,
                () ->
                        service.logRequired(
                                TurnRecord.userPrompt(1, "event"),
                                UUID.randomUUID(),
                                UUID.randomUUID(),
                                "agent"));
    }

    @Test
    void rawTurnLogCarriesTenantAndPayload() {
        TurnRecordRepository repo = mock(TurnRecordRepository.class);
        TurnLogService service = new TurnLogService(repo, new ObjectMapper(), new DeltaBroker());

        UUID session = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        String agent = UUID.randomUUID().toString();
        service.log(TurnRecord.userPrompt(7, "do the thing"), session, user, agent);

        ArgumentCaptor<TurnRecordEntity> captor = ArgumentCaptor.forClass(TurnRecordEntity.class);
        verify(repo).save(captor.capture());
        TurnRecordEntity saved = requireValue(captor.getValue(), "captured turn required");
        assertEquals(user, saved.getUserId());
        assertEquals(session.toString(), saved.getSessionId());
        assertEquals(agent, saved.getAgentId());
        assertEquals(7, saved.getTurnNumber());
        assertEquals("USER_PROMPT", saved.getType());
        assertTrue(saved.getPayload().contains("do the thing"));
    }

    @Test
    void toolCallIsLoggedForCoherentReplay() {
        TurnRecordRepository repo = mock(TurnRecordRepository.class);
        TurnLogService service = new TurnLogService(repo, new ObjectMapper(), new DeltaBroker());

        UUID session = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        // A tool call must be logged so it pairs with its tool response on replay - otherwise the
        // durable log holds an orphaned TOOL_RESPONSE that breaks PromptCompiler/the LLM API.
        ToolCall call = new ToolCall("read_file", Map.of("path", "a.txt"), "call-1");
        service.log(TurnRecord.toolCall(3, call), session, user, UUID.randomUUID().toString());

        verify(repo, times(1)).save(any(TurnRecordEntity.class));
    }

    @Test
    void disabledLoggingSkipsOptionalWritesAndRejectsRequiredWrites() {
        var repo = mock(TurnRecordRepository.class);
        var broker = mock(DeltaBroker.class);
        TurnLogService service = new TurnLogService(repo, new ObjectMapper(), broker);
        service.setEnabled(false);
        service.log(
                TurnRecord.userPrompt(1, "hello"),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID().toString());
        assertThrows(
                IllegalStateException.class,
                () ->
                        service.logRequired(
                                TurnRecord.userPrompt(2, "required"),
                                UUID.randomUUID(),
                                UUID.randomUUID(),
                                UUID.randomUUID().toString()));
        verifyNoInteractions(repo, broker);
    }

    private static <T extends @NonNull Object> @NonNull T requireValue(T value, String message) {
        if (value != null) {
            return value;
        }
        throw new AssertionError(message);
    }
}
