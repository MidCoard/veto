package top.focess.veto.memory;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import top.focess.veto.agent.TurnRecord;

/**
 * Verifies {@link TurnLogService} persists turns to the raw-turn log ({@link TurnRecordRepository})
 * only - turn persistence is session state; nothing feeds LTM (long-term memory is agent-written
 * only, via {@code write_memory}).
 */
class TurnLogServiceTest {

    @Test
    void logWritesRawTurnLog() {
        TurnRecordRepository repo = mock(TurnRecordRepository.class);
        TurnLogService service = new TurnLogService(repo, new ObjectMapper());

        UUID session = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        String agent = UUID.randomUUID().toString();
        service.log(TurnRecord.userPrompt(1, "hello world"), session, user, agent);

        verify(repo, times(1)).save(any(TurnRecordEntity.class));
    }

    @Test
    void rawTurnLogCarriesTenantAndPayload() {
        TurnRecordRepository repo = mock(TurnRecordRepository.class);
        TurnLogService service = new TurnLogService(repo, new ObjectMapper());

        UUID session = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        String agent = UUID.randomUUID().toString();
        service.log(TurnRecord.userPrompt(7, "do the thing"), session, user, agent);

        org.mockito.ArgumentCaptor<TurnRecordEntity> captor =
                org.mockito.ArgumentCaptor.forClass(TurnRecordEntity.class);
        verify(repo).save(captor.capture());
        TurnRecordEntity saved = captor.getValue();
        assertEquals(user.toString(), saved.getUserId());
        assertEquals(session.toString(), saved.getSessionId());
        assertEquals(agent, saved.getAgentId());
        assertEquals(7, saved.getTurnNumber());
        assertEquals("USER_PROMPT", saved.getType());
        assertTrue(saved.getPayload().contains("do the thing"));
    }

    @Test
    void toolCallIsLoggedForCoherentReplay() {
        TurnRecordRepository repo = mock(TurnRecordRepository.class);
        TurnLogService service = new TurnLogService(repo, new ObjectMapper());

        UUID session = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        // A tool call must be logged so it pairs with its tool response on replay - otherwise the
        // durable log holds an orphaned TOOL_RESPONSE that breaks PromptCompiler/the LLM API.
        top.focess.veto.llm.core.ToolCall call =
                new top.focess.veto.llm.core.ToolCall(
                        "read_file", java.util.Map.of("path", "a.txt"), "call-1");
        service.log(
                top.focess.veto.agent.TurnRecord.toolCall(3, call),
                session,
                user,
                UUID.randomUUID().toString());

        verify(repo, times(1)).save(any(TurnRecordEntity.class));
    }

    @Test
    void absentRepositoryIsANoOp() {
        TurnLogService service = new TurnLogService(null, new ObjectMapper());
        // Must not throw - deployments without durability simply skip logging.
        service.log(
                TurnRecord.userPrompt(1, "hello"),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID().toString());
    }
}
