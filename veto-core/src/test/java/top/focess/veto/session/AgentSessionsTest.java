package top.focess.veto.session;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import top.focess.veto.agent.RequestHandle;
import top.focess.veto.agent.intercept.VetoOption;
import top.focess.veto.agent.intercept.VetoPrompt;
import top.focess.veto.agent.intercept.VetoScenario;
import top.focess.veto.api.llm.LlmBinding;
import top.focess.veto.api.plugin.Scope;
import top.focess.veto.command.SessionCommandService;
import top.focess.veto.contract.Frame;
import top.focess.veto.vault.TestUsers;

@Timeout(10)
class AgentSessionsTest {
    private final @NonNull AgentSessions sessions = TestAgentSessions.create();
    private final Scope.@NonNull SessionScope first = scope(TestUsers.ALICE);
    private final Scope.@NonNull SessionScope second = scope(TestUsers.ALICE);

    @Test
    void terminalSelectsOneSessionWhileBrowserReceivesExistingAndFutureOwnedSessions() {
        var terminal = new Connection(TestUsers.ALICE);
        var browser = new Connection(TestUsers.ALICE);
        var foreign = new Connection(TestUsers.BOB);
        sessions.session(first);
        sessions.connectTerminal("terminal", terminal.connection);
        sessions.select("terminal", first);
        sessions.connectBrowser(browser.connection);
        sessions.connectBrowser(foreign.connection);
        sessions.session(first).send(event(first));
        sessions.session(second).send(event(second));
        assertEquals(1, terminal.delivered().size());
        assertEquals(2, browser.delivered().size());
        assertTrue(foreign.delivered().isEmpty());
        sessions.select("terminal", second);
        sessions.session(first).send(event(first));
        sessions.session(second).send(event(second));
        assertEquals(List.of(event(second)), terminal.delivered());
        assertEquals(2, browser.delivered().size());
    }

    @Test
    void switchingAwayAndBackDoesNotRevivePreviouslyQueuedOutput() {
        var connection = new Connection(TestUsers.ALICE);
        sessions.connectTerminal("terminal", connection.connection);
        sessions.select("terminal", first);
        sessions.session(first).send(event(first));
        sessions.select("terminal", second);
        sessions.select("terminal", first);
        assertTrue(connection.delivered().isEmpty());
        sessions.session(first).send(event(first));
        assertEquals(List.of(event(first)), connection.delivered());
    }

    @Test
    void disconnectAndDeletionRevokeQueuedFramesAndLatePublications() {
        var browser = new Connection(TestUsers.ALICE);
        sessions.connectBrowser(browser.connection);
        sessions.session(first).send(event(first));
        sessions.disconnect(browser.connection);
        sessions.connectBrowser(browser.connection);
        assertTrue(browser.delivered().isEmpty());
        sessions.session(first).send(event(first));
        sessions.close(first);
        sessions.session(first).send(event(first));
        assertTrue(browser.delivered().isEmpty());
        assertThrows(IllegalStateException.class, () -> sessions.select("terminal", first));
        sessions.close(second); // Deletion also precedes the first publication.
        sessions.session(second).send(event(second));
        assertTrue(browser.delivered().isEmpty());
    }

    @Test
    void logoutAndReplacementDoNotRetainTerminalAuthority() {
        var old = new Connection(TestUsers.ALICE);
        var replacement = new Connection(TestUsers.ALICE);
        sessions.connectTerminal("terminal", old.connection);
        sessions.select("terminal", first);
        sessions.session(first).send(event(first));
        sessions.disconnect(old.connection);
        sessions.connectTerminal("terminal", replacement.connection);
        assertNull(sessions.selected("terminal"));
        sessions.select("terminal", first);
        sessions.disconnect(old.connection); // A delayed close cannot detach the replacement.
        sessions.session(first).send(event(first));
        assertTrue(old.delivered().isEmpty());
        assertEquals(1, replacement.delivered().size());
        sessions.session(first).send(event(first));
        sessions.detachUser(TestUsers.ALICE);
        assertTrue(replacement.delivered().isEmpty());
        assertNull(sessions.selected("terminal"));
    }

    @Test
    void connectionConvertsExistingDomainValueAndAcceptsFramesDirectly() {
        var connection = new Connection(TestUsers.ALICE);
        sessions.connectBrowser(connection.connection);
        var scope = new Scope.AgentScope(TestUsers.ALICE, first.session(), "agent");
        var prompt =
                new VetoPrompt(
                        "agent",
                        "call",
                        "tool",
                        VetoScenario.GENERIC,
                        List.of(VetoOption.ACCEPT_GENERIC),
                        Map.of("input", "value"),
                        null,
                        null,
                        7);
        sessions.session(first).send(scope, prompt);
        var sent = connection.delivered();
        var veto = assertInstanceOf(Frame.Veto.class, sent.getFirst());
        assertEquals(scope, veto.scope());
        assertEquals(prompt.callId(), veto.callId());
        assertEquals(prompt.args(), veto.args());
        assertEquals(7, veto.seq());
        sessions.session(first).send(event(first));
        assertEquals(List.of(event(first)), connection.delivered());
    }

    @Test
    void onlyAttachedOwnedConnectionCanAccessTheSession() {
        var connection = new Connection(TestUsers.ALICE);
        var session = sessions.session(first);
        assertThrows(
                IllegalStateException.class, () -> session.requireAttached(connection.connection));
        sessions.connectTerminal("terminal", connection.connection);
        sessions.select("terminal", first);
        session.requireAttached(connection.connection);
        sessions.detach("terminal");
        assertThrows(
                IllegalStateException.class, () -> session.requireAttached(connection.connection));
        assertThrows(
                IllegalStateException.class,
                () ->
                        session.submit(
                                TestUsers.BOB,
                                "prompt",
                                mock(LlmBinding.class),
                                Locale.ENGLISH,
                                RequestHandle.Admission.DIRECT));
    }

    @Test
    void publisherDoesNotHoldAttachmentLockWhileCallingAConnection() throws Exception {
        var connection = new Connection(TestUsers.ALICE);
        sessions.connectBrowser(connection.connection);
        var disconnected = mock(ProtocolConnection.class);
        when(disconnected.userId()).thenReturn(TestUsers.ALICE);
        when(disconnected.sendSession(any(), any()))
                .thenAnswer(
                        invocation -> {
                            try (var worker = Executors.newVirtualThreadPerTaskExecutor()) {
                                worker.submit(() -> sessions.disconnect(disconnected))
                                        .get(2, TimeUnit.SECONDS);
                            }
                            return true;
                        });
        sessions.connectBrowser(disconnected);
        sessions.session(first).send(event(first));
        assertEquals(1, connection.delivered().size());
        verify(disconnected).sendSession(any(), any());
    }

    private static Scope.@NonNull SessionScope scope(@NonNull UUID user) {
        return new Scope.SessionScope(user, UUID.randomUUID().toString());
    }

    private static Frame.@NonNull Action event(Scope.@NonNull SessionScope scope) {
        return new Frame.Action(scope, List.of("agents"));
    }

    private static final class Connection {
        private final @NonNull ProtocolConnection connection;
        private final @NonNull List<@NonNull Pending> queue = new ArrayList<>();

        Connection(@NonNull UUID user) {
            connection =
                    new ProtocolConnection(
                            "fixture",
                            TestAgentSessions.create(),
                            mock(SessionService.class),
                            mock(SessionCommandService.class),
                            null,
                            user,
                            () -> true,
                            BooleanSupplier::getAsBoolean,
                            (frame, version, attached) -> {
                                synchronized (queue) {
                                    queue.add(new Pending(frame, attached));
                                }
                                return true;
                            },
                            ignored -> {},
                            Locale.ENGLISH);
        }

        synchronized @NonNull List<Frame.@NonNull ServerFrame> delivered() {
            var delivered =
                    queue.stream()
                            .filter(p -> p.attached().getAsBoolean())
                            .map(Pending::frame)
                            .toList();
            queue.clear();
            return delivered;
        }

        private record Pending(
                Frame.@NonNull ServerFrame frame, @NonNull BooleanSupplier attached) {}
    }
}
