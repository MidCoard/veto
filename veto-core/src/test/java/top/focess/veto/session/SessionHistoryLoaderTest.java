package top.focess.veto.session;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import top.focess.veto.agent.TurnRecord;
import top.focess.veto.agent.TurnType;
import top.focess.veto.memory.TurnRecordEntity;
import top.focess.veto.memory.TurnRecordRepository;

class SessionHistoryLoaderTest {

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
    void emptyWhenNoHistory() {
        TurnRecordRepository repo = mock(TurnRecordRepository.class);
        when(repo.findBySessionIdOrderByTurnNumberAsc(anyString())).thenReturn(List.of());
        SessionHistoryLoader loader = new SessionHistoryLoader(repo, new ObjectMapper());
        assertTrue(loader.load("no-such-session").isEmpty());
    }
}
