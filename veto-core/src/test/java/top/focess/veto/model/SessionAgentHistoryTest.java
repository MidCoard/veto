package top.focess.veto.model;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import top.focess.veto.agent.AgentRunner;
import top.focess.veto.agent.AgentState;
import top.focess.veto.agent.SessionAgentRegistry;
import top.focess.veto.agent.TurnRecord;
import top.focess.veto.agent.TurnType;
import top.focess.veto.agent.VetoAgent;
import top.focess.veto.agent.identity.AgentPersona;
import top.focess.veto.agent.identity.Role;
import top.focess.veto.memory.TurnLogService;
import top.focess.veto.memory.TurnRecordEntity;
import top.focess.veto.memory.TurnRecordRepository;
import top.focess.veto.session.SessionHistoryLoader;

@DataJpaTest
@SuppressWarnings("initialization.field.uninitialized")
class SessionAgentHistoryTest {
    @Autowired private @NonNull AgentInstanceRepository repository;
    @Autowired private @NonNull TurnRecordRepository turns;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void committedFailuresReloadInTheirOriginalAgentAndSessionStreams() {
        UUID session = UUID.randomUUID();
        UUID otherSession = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        String primary = UUID.randomUUID().toString();
        String mate = UUID.randomUUID().toString();
        var mapper = new ObjectMapper();
        var writer = new TurnLogService(turns, mapper);
        try {
            writer.log(TurnRecord.userPrompt(1, "Original request"), session, owner, primary);
            writer.log(
                    new TurnRecord(
                            2,
                            TurnType.EXECUTION_ERROR,
                            Map.of("content", "Primary failure", "requestId", "request-a"),
                            null),
                    session,
                    owner,
                    primary);
            writer.log(
                    new TurnRecord(
                            2,
                            TurnType.EXECUTION_ERROR,
                            Map.of("content", "Mate failure", "requestId", "request-b"),
                            null),
                    session,
                    owner,
                    mate);
            writer.log(
                    new TurnRecord(
                            2,
                            TurnType.EXECUTION_ERROR,
                            Map.of("content", "Other session failure"),
                            null),
                    otherSession,
                    owner,
                    primary);

            var reader = new SessionHistoryLoader(turns, new ObjectMapper());
            var restored = reader.load(session.toString(), primary);
            assertEquals(
                    List.of(TurnType.USER_PROMPT, TurnType.EXECUTION_ERROR),
                    restored.stream().map(TurnRecord::type).toList());
            assertEquals("Primary failure", restored.getLast().payload().get("content"));
            assertEquals("request-a", restored.getLast().payload().get("requestId"));
            assertEquals(
                    "Mate failure",
                    reader.load(session.toString(), mate).getFirst().payload().get("content"));
            assertEquals(
                    "Other session failure",
                    reader.load(otherSession.toString(), primary)
                            .getFirst()
                            .payload()
                            .get("content"));
            assertTrue(reader.load(otherSession.toString(), mate).isEmpty());
            var row =
                    turns.findBySessionIdAndAgentIdAndTurnNumber(session.toString(), primary, 2)
                            .orElseThrow();
            assertEquals(owner.toString(), row.getUserId());
            assertEquals(restored.getLast().timestamp(), row.getTimestamp());
        } finally {
            turns.deleteAll(turns.findBySessionIdOrderByTurnNumberAsc(session.toString()));
            turns.deleteAll(turns.findBySessionIdOrderByTurnNumberAsc(otherSession.toString()));
        }
    }

    @Test
    void olderTurnStreamsRemainVisibleWithoutInventingMetadata() {
        UUID sessionId = UUID.randomUUID();
        turns.save(
                TurnRecordEntity.of(
                        TurnRecord.assistantResponse(1, "done"),
                        sessionId,
                        UUID.randomUUID(),
                        "older-agent",
                        new ObjectMapper()));
        var registry = new SessionAgentRegistry(repository, turns);
        var history = registry.records(sessionId);
        assertEquals(1, history.size());
        assertEquals("older-agent", history.getFirst().id());
        assertNull(history.getFirst().role());
        assertNull(history.getFirst().endedAt());
        assertFalse(history.getFirst().live());
    }

    @Test
    void idleParentAndEndedChildRemainAfterRegistryRecreation() {
        UUID sessionId = UUID.randomUUID();
        AgentEntity primary =
                repository.save(
                        new AgentEntity(
                                sessionId.toString(),
                                null,
                                AgentEntity.Role.PRIMARY,
                                "My assistant",
                                "DEEPSEEK",
                                "model",
                                "key"));
        SessionAgentRegistry registry = new SessionAgentRegistry(repository, turns);
        assertEquals(1, registry.records(sessionId).size());
        assertFalse(registry.records(sessionId).getFirst().live());

        AgentPersona parentPersona =
                new AgentPersona(primary.getId(), "Main", "", Set.of(), List.of());
        @NonNull VetoAgent parent = mock();
        when(parent.id()).thenReturn(primary.getId());
        when(parent.name()).thenReturn("Main");
        when(parent.persona()).thenReturn(parentPersona);
        when(parent.state()).thenReturn(AgentState.IDLE);
        when(parent.userInteractionEnabled()).thenReturn(true);
        registry.register(sessionId, parent);
        assertEquals(AgentState.IDLE, registry.records(sessionId).getFirst().state());
        assertEquals("My assistant", registry.records(sessionId).getFirst().name());

        AgentPersona reader = new AgentPersona("reader", "Web reader", "", Set.of(), List.of());
        @NonNull AgentRunner runner = mock();
        when(runner.personaView()).thenReturn(reader);
        when(runner.state()).thenReturn(AgentState.IDLE);
        registry.startChild(sessionId, primary.getId(), "fetch-1", reader, runner);
        registry.stop("reader");
        var stopped =
                registry.records(sessionId).stream()
                        .filter(a -> a.id().equals("reader"))
                        .findFirst()
                        .orElseThrow();
        assertEquals(Role.STANDALONE, stopped.role());
        assertEquals(AgentState.TERMINATED, stopped.state());
        assertEquals(primary.getId(), stopped.parentAgentId());
        assertEquals("fetch-1", stopped.parentCallId());
        assertTrue(stopped.endedAt() != null);
        assertFalse(stopped.live());
        assertFalse(stopped.userInteractionEnabled());
        assertTrue(registry.records(UUID.randomUUID()).isEmpty());

        registry.close();
        repository.flush();
        SessionAgentRegistry restarted = new SessionAgentRegistry(repository, turns);
        assertEquals(2, restarted.records(sessionId).size());
        assertTrue(
                restarted.records(sessionId).stream()
                        .filter(agent -> agent.id().equals(primary.getId()))
                        .findFirst()
                        .orElseThrow()
                        .userInteractionEnabled());
        assertFalse(
                restarted.records(sessionId).stream()
                        .filter(agent -> agent.id().equals("reader"))
                        .findFirst()
                        .orElseThrow()
                        .userInteractionEnabled());
        assertTrue(
                restarted.records(sessionId).stream()
                        .noneMatch(SessionAgentRegistry.AgentSummary::live));
        assertEquals(2, repository.findBySessionId(sessionId.toString()).size());
    }
}
