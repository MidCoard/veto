package top.focess.veto.agent;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.veto.agent.identity.AgentPersona;
import top.focess.veto.agent.identity.Role;
import top.focess.veto.agent.tool.ToolDocs;

class SessionAgentRegistryTest {
    @Test
    void independentAgentJoinsActiveSessionButCannotRestartRemovedSession() {
        SessionAgentRegistry registry = new SessionAgentRegistry();
        UUID session = UUID.randomUUID();
        AgentRunner rootRunner = runner(persona("root", Role.STANDALONE));
        registry.register(session, new VetoAgent(persona("root", Role.STANDALONE), rootRunner));
        AgentPersona peerPersona = persona("peer", Role.STANDALONE);
        AgentRunner peerRunner = runner(peerPersona);
        when(peerRunner.sessionId()).thenReturn(session);
        VetoAgent peer = registry.startInSession(session, peerPersona, peerRunner);
        verify(peerRunner).setSessionId(session);
        assertEquals(Set.of("root", "peer"), ids(registry, session));
        var entry =
                registry.agents(session).stream()
                        .filter(value -> value.agent() == peer)
                        .findFirst()
                        .orElseThrow();
        assertNull(entry.parentAgentId());
        assertNull(entry.parentCallId());
        registry.stopSession(session);
        assertTrue(registry.agents(session).isEmpty());
        verify(rootRunner).terminate();
        verify(peerRunner).terminate();
        AgentPersona latePersona = persona("late", Role.STANDALONE);
        AgentRunner lateRunner = runner(latePersona);
        when(lateRunner.sessionId()).thenReturn(session);
        assertThrows(
                IllegalStateException.class,
                () -> registry.startInSession(session, latePersona, lateRunner));
        verify(lateRunner, never()).run();
        verify(lateRunner, never()).setSessionId(any());
        assertTrue(registry.agents(session).isEmpty());
        registry.close();
    }

    @Test
    void parentTerminationCancelsDescendantsWithoutAffectingSessionPeers() {
        SessionAgentRegistry registry = new SessionAgentRegistry();
        UUID session = UUID.randomUUID();
        UUID otherSession = UUID.randomUUID();
        AgentRunner parentRunner = runner(persona("main", Role.STANDALONE));
        VetoAgent parent = new VetoAgent(persona("main", Role.STANDALONE), parentRunner);
        registry.register(session, parent);
        AgentRunner mateRunner = runner(persona("mate", Role.MATE));
        VetoAgent mate = new VetoAgent(persona("mate", Role.MATE), mateRunner);
        registry.register(session, mate);
        AgentRunner otherRunner = runner(persona("other", Role.STANDALONE));
        registry.register(
                otherSession, new VetoAgent(persona("other", Role.STANDALONE), otherRunner));
        AgentRunner readerRunner = runner(persona("reader", Role.STANDALONE));
        VetoAgent reader =
                registry.startChild(
                        session,
                        "main",
                        "read-call",
                        persona("reader", Role.STANDALONE),
                        readerRunner);
        AgentRunner nestedRunner = runner(persona("nested", Role.STANDALONE));
        registry.startChild(
                session, "reader", "nested-call", persona("nested", Role.STANDALONE), nestedRunner);
        AgentRunner mateReaderRunner = runner(persona("mate-reader", Role.STANDALONE));
        registry.startChild(
                session,
                "mate",
                "mate-read-call",
                persona("mate-reader", Role.STANDALONE),
                mateReaderRunner);

        var entry =
                registry.agents(session).stream()
                        .filter(value -> value.agent() == reader)
                        .findFirst()
                        .orElseThrow();
        assertEquals("main", entry.parentAgentId());
        assertEquals("read-call", entry.parentCallId());
        assertEquals(Role.STANDALONE, reader.persona().role());
        when(parentRunner.personaView()).thenReturn(persona("main", Role.LEADER));
        assertEquals(Role.STANDALONE, reader.persona().role());
        assertEquals(5, registry.agents(session).size());

        parent.terminate();
        assertEquals(Set.of("mate", "mate-reader"), ids(registry, session));
        verify(readerRunner).terminate();
        verify(nestedRunner).terminate();
        verify(mateRunner, never()).terminate();
        verify(mateReaderRunner, never()).terminate();
        verify(otherRunner, never()).terminate();
        registry.stopSession(session);
        assertTrue(registry.agents(session).isEmpty());
        verify(mateRunner).terminate();
        verify(mateReaderRunner).terminate();
        assertEquals(Set.of("other"), ids(registry, otherSession));
        registry.close();
        verify(otherRunner).terminate();
    }

    @Test
    void completingOneChildKeepsItsParentAndSiblingAlive() {
        SessionAgentRegistry registry = new SessionAgentRegistry();
        UUID session = UUID.randomUUID();
        AgentRunner parentRunner = runner(persona("parent", Role.STANDALONE));
        registry.register(session, new VetoAgent(persona("parent", Role.STANDALONE), parentRunner));
        AgentRunner firstRunner = runner(persona("first", Role.STANDALONE));
        AgentRunner secondRunner = runner(persona("second", Role.STANDALONE));
        registry.startChild(
                session, "parent", "first-call", persona("first", Role.STANDALONE), firstRunner);
        registry.startChild(
                session, "parent", "second-call", persona("second", Role.STANDALONE), secondRunner);
        registry.stop("first");
        registry.stop("first");
        assertEquals(Set.of("parent", "second"), ids(registry, session));
        verify(firstRunner).terminate();
        verify(parentRunner, never()).terminate();
        verify(secondRunner, never()).terminate();
        registry.close();
    }

    @Test
    void rejectsMissingCrossSessionTerminatedAndDuplicateParentsOrChildren() {
        SessionAgentRegistry registry = new SessionAgentRegistry();
        UUID session = UUID.randomUUID();
        AgentRunner parentRunner = runner(persona("parent", Role.STANDALONE));
        VetoAgent parent = new VetoAgent(persona("parent", Role.STANDALONE), parentRunner);
        registry.register(session, parent);
        AgentRunner childRunner = runner(persona("child", Role.STANDALONE));
        assertThrows(
                IllegalStateException.class,
                () ->
                        registry.startChild(
                                session,
                                "missing",
                                "call",
                                persona("child", Role.STANDALONE),
                                childRunner));
        assertThrows(
                IllegalStateException.class,
                () ->
                        registry.startChild(
                                UUID.randomUUID(),
                                "parent",
                                "call",
                                persona("child", Role.STANDALONE),
                                childRunner));
        assertThrows(IllegalStateException.class, () -> registry.register(session, parent));
        assertThrows(
                IllegalStateException.class,
                () ->
                        registry.startChild(
                                session,
                                "parent",
                                "call",
                                persona("parent", Role.STANDALONE),
                                childRunner));
        when(parentRunner.state()).thenReturn(AgentState.TERMINATED);
        assertThrows(
                IllegalStateException.class,
                () ->
                        registry.startChild(
                                session,
                                "parent",
                                "call",
                                persona("child", Role.STANDALONE),
                                childRunner));
        verify(childRunner, never()).run();
        registry.close();
        assertThrows(IllegalStateException.class, () -> registry.register(session, parent));
        assertThrows(
                IllegalStateException.class,
                () ->
                        registry.startChild(
                                session,
                                "parent",
                                "call",
                                persona("child", Role.STANDALONE),
                                childRunner));
    }

    @Test
    void sessionStopRejectsAChildAlreadyWaitingToRegister() throws Exception {
        SessionAgentRegistry registry = new SessionAgentRegistry();
        UUID session = UUID.randomUUID();
        AgentRunner parentRunner = runner(persona("parent", Role.STANDALONE));
        registry.register(session, new VetoAgent(persona("parent", Role.STANDALONE), parentRunner));
        CountDownLatch registering = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AgentRunner childRunner = runner(persona("child", Role.STANDALONE));
        Thread starter;
        synchronized (registry) {
            starter =
                    Thread.ofVirtual()
                            .start(
                                    () -> {
                                        registering.countDown();
                                        try {
                                            registry.startChild(
                                                    session,
                                                    "parent",
                                                    "call",
                                                    persona("child", Role.STANDALONE),
                                                    childRunner);
                                        } catch (Throwable error) {
                                            failure.set(error);
                                        }
                                    });
            assertTrue(registering.await(3, TimeUnit.SECONDS));
            registry.stopSession(session);
        }
        try {
            starter.join(3000);
            assertFalse(starter.isAlive());
            assertInstanceOf(IllegalStateException.class, failure.get());
            assertTrue(registry.agents(session).isEmpty());
            verify(parentRunner).terminate();
            verify(childRunner, never()).run();
        } finally {
            starter.interrupt();
            registry.close();
        }
    }

    private static @NonNull AgentPersona persona(@NonNull String id, @NonNull Role role) {
        return new AgentPersona(id, id, "Test agent", Set.of(), List.of(), role);
    }

    private static @NonNull AgentRunner runner(@NonNull AgentPersona persona) {
        AgentRunner runner = mock(ToolDocs.nonNullClass(AgentRunner.class));
        AtomicReference<AgentState> state = new AtomicReference<>(AgentState.IDLE);
        when(runner.state()).thenAnswer(invocation -> state.get());
        when(runner.personaView()).thenReturn(persona);
        AtomicReference<Runnable> termination = new AtomicReference<>();
        doAnswer(
                        invocation -> {
                            termination.set(invocation.getArgument(0));
                            return null;
                        })
                .when(runner)
                .onTermination(any());
        doAnswer(
                        invocation -> {
                            state.set(AgentState.TERMINATED);
                            Runnable callback = termination.get();
                            if (callback != null) callback.run();
                            return null;
                        })
                .when(runner)
                .terminate();
        return runner;
    }

    private static @NonNull Set<@NonNull String> ids(
            @NonNull SessionAgentRegistry registry, @NonNull UUID session) {
        return Set.copyOf(
                registry.agents(session).stream().map(entry -> entry.agent().id()).toList());
    }
}
