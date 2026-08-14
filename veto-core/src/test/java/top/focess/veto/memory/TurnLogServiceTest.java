package top.focess.veto.memory;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
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
        @NonNull TurnRecordRepository repo =
                mock(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(TurnRecordRepository.class));
        @NonNull TurnLogService service = new TurnLogService(repo, new ObjectMapper());

        @NonNull UUID session = UUID.randomUUID();
        @NonNull UUID user = UUID.randomUUID();
        @NonNull String agent = UUID.randomUUID().toString();
        service.log(TurnRecord.userPrompt(1, "hello world"), session, user, agent);

        verify(repo, times(1))
                .save(any(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(TurnRecordEntity.class)));
    }

    @Test
    void rawTurnLogCarriesTenantAndPayload() {
        @NonNull TurnRecordRepository repo =
                mock(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(TurnRecordRepository.class));
        @NonNull TurnLogService service = new TurnLogService(repo, new ObjectMapper());

        @NonNull UUID session = UUID.randomUUID();
        @NonNull UUID user = UUID.randomUUID();
        @NonNull String agent = UUID.randomUUID().toString();
        service.log(TurnRecord.userPrompt(7, "do the thing"), session, user, agent);

        org.mockito.@NonNull ArgumentCaptor<TurnRecordEntity> captor =
                org.mockito.ArgumentCaptor.forClass(
                        top.focess.veto.agent.mcp.ToolDocs.nonNullClass(TurnRecordEntity.class));
        verify(repo).save(captor.capture());
        @NonNull TurnRecordEntity saved = requireValue(captor.getValue(), "captured turn required");
        assertEquals(user.toString(), saved.getUserId());
        assertEquals(session.toString(), saved.getSessionId());
        assertEquals(agent, saved.getAgentId());
        assertEquals(7, saved.getTurnNumber());
        assertEquals("USER_PROMPT", saved.getType());
        assertTrue(saved.getPayload().contains("do the thing"));
    }

    @Test
    void toolCallIsLoggedForCoherentReplay() {
        @NonNull TurnRecordRepository repo =
                mock(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(TurnRecordRepository.class));
        @NonNull TurnLogService service = new TurnLogService(repo, new ObjectMapper());

        @NonNull UUID session = UUID.randomUUID();
        @NonNull UUID user = UUID.randomUUID();
        // A tool call must be logged so it pairs with its tool response on replay - otherwise the
        // durable log holds an orphaned TOOL_RESPONSE that breaks PromptCompiler/the LLM API.
        top.focess.veto.llm.core.@NonNull ToolCall call =
                new top.focess.veto.llm.core.ToolCall(
                        "read_file", java.util.Map.of("path", "a.txt"), "call-1");
        service.log(
                top.focess.veto.agent.TurnRecord.toolCall(3, call),
                session,
                user,
                UUID.randomUUID().toString());

        verify(repo, times(1))
                .save(any(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(TurnRecordEntity.class)));
    }

    @Test
    void absentRepositoryIsANoOp() {
        @NonNull TurnLogService service = new TurnLogService(null, new ObjectMapper());
        // Must not throw - deployments without durability simply skip logging.
        service.log(
                TurnRecord.userPrompt(1, "hello"),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID().toString());
    }

    private static <T extends @NonNull Object> @NonNull T requireValue(T value, String message) {
        if (value != null) {
            return value;
        }
        throw new AssertionError(message);
    }
}
