package top.focess.veto.memory;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import top.focess.veto.agent.TurnRecord;
import top.focess.veto.api.llm.ToolCall;
import top.focess.veto.bus.SessionActions;

/**
 * Verifies {@link TurnLogService} persists turns to the raw-turn log ({@link TurnRecordRepository})
 * only - turn persistence is session state; nothing feeds LTM (long-term memory is agent-written
 * only, via {@code write_memory}).
 */
class TurnLogServiceTest {

    @Test
    void publishesResourceChangesOnlyAfterSuccessfulPersistence() {
        var repo = mock(TurnRecordRepository.class);
        var actions = mock(SessionActions.class);
        var service = new TurnLogService(repo, new ObjectMapper(), actions);
        var session = UUID.randomUUID();
        var owner = UUID.randomUUID();
        service.log(TurnRecord.userPrompt(1, "hello"), session, owner, "agent");
        var order = inOrder(repo, actions);
        order.verify(repo).save(any());
        order.verify(actions).changed(session, "records", "history");
        clearInvocations(actions);
        when(repo.save(any())).thenThrow(new IllegalStateException("offline"));
        service.log(TurnRecord.userPrompt(2, "later"), session, owner, "agent");
        verifyNoInteractions(actions);
        when(repo.updateRecordMetadata(
                        anyString(), any(), anyString(), anyInt(), anyString(), anyString()))
                .thenReturn(1);
        service.updateMetadata(TurnRecord.userPrompt(1, "hello"), session, owner, "agent");
        verify(actions).changed(session, "records", "history");
    }

    @Test
    void metadataUpdatesRemainPersistenceOperations() {
        var repo = mock(TurnRecordRepository.class);
        var service =
                new TurnLogService(repo, new ObjectMapper(), Mockito.mock(SessionActions.class));
        var session = UUID.randomUUID();
        var owner = UUID.randomUUID();
        service.updateMetadata(TurnRecord.userPrompt(1, "hello"), session, owner, "agent");
        verify(repo)
                .updateRecordMetadata(
                        eq(session.toString()),
                        eq(owner),
                        eq("agent"),
                        eq(1),
                        anyString(),
                        anyString());
    }

    @Test
    void requiredLoggingPropagatesStorageFailure() {
        TurnRecordRepository repo = mock(TurnRecordRepository.class);
        var service =
                new TurnLogService(repo, new ObjectMapper(), Mockito.mock(SessionActions.class));
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
        TurnLogService service =
                new TurnLogService(repo, new ObjectMapper(), Mockito.mock(SessionActions.class));

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
        TurnLogService service =
                new TurnLogService(repo, new ObjectMapper(), Mockito.mock(SessionActions.class));

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
        TurnLogService service =
                new TurnLogService(repo, new ObjectMapper(), Mockito.mock(SessionActions.class));
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
        verifyNoInteractions(repo);
    }

    private static <T extends @NonNull Object> @NonNull T requireValue(T value, String message) {
        if (value != null) {
            return value;
        }
        throw new AssertionError(message);
    }
}
