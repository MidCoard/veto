package top.focess.veto.agent;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static top.focess.veto.util.Nullness.requireNonNull;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import top.focess.veto.memory.TurnLogService;

class AgentHistoryTest {
    @Test
    void requiredWriteFailureDoesNotBecomeVisibleOrConsumeTurnNumber() {
        var log = mock(requireNonNull(TurnLogService.class));
        var session = UUID.randomUUID();
        var user = UUID.randomUUID();
        var history = new AgentHistory(log, () -> session, user, "agent");
        history.seed(List.of(TurnRecord.userPrompt(7, "persisted")));
        doThrow(new IllegalStateException("storage unavailable"))
                .when(log)
                .logRequired(any(), eq(session), eq(user), eq("agent"));

        assertThrows(
                IllegalStateException.class,
                () -> history.append(TurnRecord.userPrompt(7, "required"), true));
        assertEquals(1, history.snapshot().size());
        doNothing().when(log).logRequired(any(), eq(session), eq(user), eq("agent"));
        assertEquals(8, history.append(TurnRecord.userPrompt(7, "retried"), true).turnNumber());
    }

    @Test
    void snapshotsStayImmutableAndOrdinaryLogFailureDoesNotStopExecution() {
        var log = mock(requireNonNull(TurnLogService.class));
        var session = UUID.randomUUID();
        var user = UUID.randomUUID();
        var history = new AgentHistory(log, () -> session, user, "agent");
        doThrow(new IllegalStateException("storage unavailable"))
                .when(log)
                .log(any(), eq(session), eq(user), eq("agent"));
        var before = history.snapshot();

        history.append(TurnRecord.userPrompt(1, "first"), false);

        assertTrue(before.isEmpty());
        assertEquals(1, history.snapshot().size());
        assertThrows(UnsupportedOperationException.class, () -> history.snapshot().clear());
    }
}
