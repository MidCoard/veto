package top.focess.veto.session;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import top.focess.veto.agent.TurnRecord;
import top.focess.veto.agent.TurnType;
import top.focess.veto.memory.TurnRecordEntity;
import top.focess.veto.memory.TurnRecordRepository;

class SessionHistoryLoaderTest {

    @Test
    void corruptHistoryCannotBecomeAnEmptyPayloadOrPartialReplay() {
        UUID session = UUID.randomUUID();
        String agent = "primary";
        var mapper = new ObjectMapper();
        var valid =
                TurnRecordEntity.of(
                        TurnRecord.userPrompt(1, "first"),
                        session,
                        UUID.randomUUID(),
                        agent,
                        mapper);
        var corrupt = mock(TurnRecordEntity.class);
        when(corrupt.getId()).thenReturn("corrupt-row");
        when(corrupt.getTurnNumber()).thenReturn(2);
        when(corrupt.getType()).thenReturn("REWIND");
        var repo = mock(TurnRecordRepository.class);
        when(repo.findBySessionIdAndAgentIdOrderByTurnNumberAsc(session.toString(), agent))
                .thenReturn(List.of(valid, corrupt));
        var loader = new SessionHistoryLoader(repo, mapper);

        for (String payload : List.of("{broken", "null", "[]", "")) {
            when(corrupt.getPayload()).thenReturn(payload);
            var failure =
                    assertThrows(
                            IllegalStateException.class,
                            () -> loader.load(session.toString(), agent));
            assertEquals(
                    "Cannot load turn record corrupt-row in session " + session,
                    failure.getMessage());
        }
        when(corrupt.getPayload()).thenReturn("{}");
        when(corrupt.getType()).thenReturn("UNKNOWN_TURN");
        assertThrows(IllegalStateException.class, () -> loader.load(session.toString(), agent));
        when(corrupt.getType()).thenReturn("ASSISTANT_RESPONSE");
        when(corrupt.getLlmUsage()).thenReturn("{broken");
        assertThrows(IllegalStateException.class, () -> loader.load(session.toString(), agent));
        verify(repo, never()).save(any());
    }

    @Test
    void loadsTurnsInOrder() {
        UUID session = UUID.randomUUID();
        String agent = UUID.randomUUID().toString();
        TurnRecordEntity row1 =
                TurnRecordEntity.of(
                        TurnRecord.userPrompt(1, "hello"),
                        session,
                        UUID.randomUUID(),
                        agent,
                        new ObjectMapper());
        TurnRecordEntity row2 =
                TurnRecordEntity.of(
                        TurnRecord.assistantResponse(2, "hi there"),
                        session,
                        UUID.randomUUID(),
                        agent,
                        new ObjectMapper());

        TurnRecordRepository repo = mock(TurnRecordRepository.class);
        when(repo.findBySessionIdOrderByTurnNumberAsc(session.toString()))
                .thenReturn(List.of(row1, row2));

        SessionHistoryLoader loader = new SessionHistoryLoader(repo, new ObjectMapper());
        List<TurnRecord> history = loader.load(session.toString());

        assertEquals(2, history.size());
        assertEquals(TurnType.USER_PROMPT, history.get(0).type());
        assertEquals("hello", history.get(0).payload().get("content"));
        assertEquals(TurnType.ASSISTANT_RESPONSE, history.get(1).type());
        assertEquals(2, history.get(1).turnNumber());
    }

    @Test
    void loadsOnlyTheRequestedAgentStream() {
        UUID session = UUID.randomUUID();
        String leader = UUID.randomUUID().toString();
        String mate = UUID.randomUUID().toString();
        TurnRecordEntity leaderTurn =
                TurnRecordEntity.of(
                        TurnRecord.userPrompt(1, "leader prompt"),
                        session,
                        UUID.randomUUID(),
                        leader,
                        new ObjectMapper());
        TurnRecordEntity mateTurn =
                TurnRecordEntity.of(
                        TurnRecord.assistantResponse(1, "mate reply"),
                        session,
                        UUID.randomUUID(),
                        mate,
                        new ObjectMapper());

        TurnRecordRepository repo = mock(TurnRecordRepository.class);
        when(repo.findBySessionIdAndAgentIdOrderByTurnNumberAsc(session.toString(), mate))
                .thenReturn(List.of(mateTurn));

        SessionHistoryLoader loader = new SessionHistoryLoader(repo, new ObjectMapper());
        List<TurnRecord> history = loader.load(session.toString(), mate);

        assertEquals(1, history.size());
        assertEquals("mate reply", history.get(0).payload().get("content"));
        // The mate stream must not surface the leader's turn.
        verify(repo, never())
                .findBySessionIdAndAgentIdOrderByTurnNumberAsc(eq(session.toString()), eq(leader));
        verify(repo, never()).findBySessionIdOrderByTurnNumberAsc(anyString());
    }

    @Test
    void normalizesLegacyDuplicateTurnTypesAtTheReadBoundary() {
        UUID session = UUID.randomUUID();
        TurnRecordEntity systemPrompt = mock(TurnRecordEntity.class);
        when(systemPrompt.getTurnNumber()).thenReturn(1);
        when(systemPrompt.getType()).thenReturn("SYSTEM_PROMPT");
        when(systemPrompt.getPayload())
                .thenReturn(
                        "{\"content\":\"legacy"
                                + " prompt\",\"provider\":\"DEEPSEEK\",\"model\":\"legacy-model\"}");
        when(systemPrompt.getTimestamp()).thenReturn(Instant.EPOCH);

        TurnRecordEntity recall = mock(TurnRecordEntity.class);
        when(recall.getTurnNumber()).thenReturn(2);
        when(recall.getType()).thenReturn("RECALL");
        when(recall.getPayload()).thenReturn("{\"from_index\":0,\"content\":\"resume here\"}");
        when(recall.getTimestamp()).thenReturn(Instant.EPOCH);

        TurnRecordRepository repo = mock(TurnRecordRepository.class);
        when(repo.findBySessionIdOrderByTurnNumberAsc(session.toString()))
                .thenReturn(List.of(systemPrompt, recall));

        List<TurnRecord> history =
                new SessionHistoryLoader(repo, new ObjectMapper()).load(session.toString());

        assertEquals(TurnType.AGENT_INIT, history.get(0).type());
        assertFalse(history.get(0).payload().containsKey("role"));
        assertEquals("legacy prompt", history.get(0).payload().get("system_prompt"));
        assertFalse(history.get(0).payload().containsKey("content"));
        assertEquals(TurnType.REWIND, history.get(1).type());
        assertEquals("resume here", history.get(1).payload().get("content"));
    }

    @Test
    void emptyWhenNoHistory() {
        TurnRecordRepository repo = mock(TurnRecordRepository.class);
        when(repo.findBySessionIdOrderByTurnNumberAsc(anyString())).thenReturn(List.of());
        SessionHistoryLoader loader = new SessionHistoryLoader(repo, new ObjectMapper());
        assertTrue(loader.load("no-such-session").isEmpty());
    }
}
