package top.focess.veto.terminal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import top.focess.veto.agent.AgentService;
import top.focess.veto.agent.intercept.VetoOption;
import top.focess.veto.agent.intercept.VetoPrompt;
import top.focess.veto.agent.intercept.VetoScenario;
import top.focess.veto.api.plugin.Scope;
import top.focess.veto.command.CommandRegistry;
import top.focess.veto.command.PromptHandler;
import top.focess.veto.command.SessionCommandService;
import top.focess.veto.command.VetoCommandSender;
import top.focess.veto.contract.Delta;
import top.focess.veto.contract.Frame;
import top.focess.veto.contract.Version;
import top.focess.veto.event.EventManager;
import top.focess.veto.model.SessionEntity;
import top.focess.veto.model.SessionRepository;
import top.focess.veto.session.AgentSessions;
import top.focess.veto.session.SessionService;
import top.focess.veto.session.TestAgentSessions;
import top.focess.veto.transport.tcp.TcpConnection;
import top.focess.veto.vault.AuthLifecycleManager;
import top.focess.veto.vault.CurrentUser;
import top.focess.veto.vault.KeysteadVault;
import top.focess.veto.vault.LoginSessionManager;
import top.focess.veto.vault.TestUsers;

class IpcPeerIsolationTest {
    private static final @NonNull AtomicLong sequences = new AtomicLong(100);

    private static long nextSequence() {
        return sequences.incrementAndGet();
    }

    private static Frame.@NonNull Request requestFrame(@NonNull String raw) {
        return new Frame.Request(nextSequence(), null, null, raw);
    }

    private static Frame.@NonNull Done done(String content) {
        return new Frame.Done(new Scope.GlobalScope(), null, -1, content, null, 0);
    }

    private static void publish(@NonNull AgentSessions runtime, Frame.@NonNull ScopedFrame frame) {
        var user = frame.scope().userId();
        var session = frame.scope().session();
        if (user == null || session == null) throw new AssertionError("Session scope required");
        runtime.session(new Scope.SessionScope(user, session)).send(frame);
    }

    private static @NonNull AnnotationConfigApplicationContext openEndpoint(
            @NonNull CommandRegistry registry,
            @NonNull AgentService agents,
            @NonNull AgentSessions runtime,
            @NonNull String address) {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment()
                .getPropertySources()
                .addFirst(
                        new MapPropertySource(
                                "terminal-test",
                                Map.of(
                                        "veto.terminal.enabled",
                                        "true",
                                        "veto.terminal.bind-address",
                                        address)));
        context.registerBean(CommandRegistry.class, () -> registry);
        context.registerBean(AgentService.class, () -> agents);
        context.registerBean(AgentSessions.class, () -> runtime);
        context.registerBean(SessionService.class, () -> mock(SessionService.class));
        context.registerBean(SessionCommandService.class, () -> mock(SessionCommandService.class));
        context.register(TerminalConfiguration.class, IpcServer.class);
        context.refresh();
        return context;
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @Timeout(20)
    void revokedCommandCannotPublishLatePrivateOutputOrFailure(boolean loginAgain)
            throws Exception {
        int port;
        try (var reservation = new ServerSocket(0)) {
            port = reservation.getLocalPort();
        }
        var alice = TestUsers.registry().findByUserId(TestUsers.ALICE).orElseThrow();
        var registry = mock(CommandRegistry.class);
        var senderRef = new AtomicReference<VetoCommandSender>();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doCallRealMethod().when(registry).terminalOutcome(any(), any());
        when(registry.dispatch(any(), anyString()))
                .thenAnswer(
                        invocation -> {
                            VetoCommandSender sender = invocation.getArgument(0);
                            if (sender == null) throw new AssertionError("Missing sender");
                            String raw = invocation.getArgument(1);
                            senderRef.set(sender);
                            if ("login".equals(raw)) {
                                sender.setUser(alice);
                                return done("fresh login");
                            }
                            if ("private".equals(raw)) {
                                entered.countDown();
                                assertTrue(release.await(5, TimeUnit.SECONDS));
                                sender.output("private credential title");
                                return Frame.Error.ofError("private command failure");
                            }
                            if ("logout".equals(raw)) {
                                sender.setUser(null);
                                sender.output("logged out");
                                return done("logout complete");
                            }
                            sender.output("fresh command output");
                            return done("fresh command complete");
                        });
        var address = "tcp://127.0.0.1:" + port;
        var endpoint =
                openEndpoint(
                        registry, mock(AgentService.class), TestAgentSessions.create(), address);
        var server = endpoint.getBean(IpcServer.class);
        try (var terminal = connect(address)) {
            assertEquals("fresh login", request(terminal, "login").content());
            terminal.send(requestFrame("private"));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            server.revokeUser(TestUsers.ALICE);
            var sender = senderRef.get();
            if (sender == null) throw new AssertionError("Missing sender");
            if (loginAgain) sender.setUser(alice);
            release.countDown();
            var completed = assertInstanceOf(Frame.Done.class, receive(terminal));

            assertNull(completed.content());
            assertEquals(loginAgain ? TestUsers.ALICE : null, completed.scope().userId());
            assertEquals("fresh login", request(terminal, "login").content());
            terminal.send(requestFrame("fresh"));
            var output = assertInstanceOf(Delta.class, receive(terminal));
            assertEquals(
                    "fresh command output",
                    assertInstanceOf(Delta.Message.class, output.data()).text());
            var fresh = assertInstanceOf(Frame.Done.class, receive(terminal));

            assertEquals("fresh command complete", fresh.content());
            terminal.send(requestFrame("logout"));
            assertInstanceOf(Delta.class, receive(terminal));
            var loggedOut = assertInstanceOf(Frame.Done.class, receive(terminal));

            assertNull(loggedOut.username());
            assertEquals("logout complete", loggedOut.content());
        } finally {
            release.countDown();
            endpoint.close();
        }
    }

    @Test
    @Timeout(20)
    void revokedCompletionLookupCannotPublishPrivateCandidates() throws Exception {
        int port;
        try (var reservation = new ServerSocket(0)) {
            port = reservation.getLocalPort();
        }
        var alice = TestUsers.registry().findByUserId(TestUsers.ALICE).orElseThrow();
        var registry = mock(CommandRegistry.class);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doCallRealMethod().when(registry).terminalOutcome(any(), any());
        when(registry.dispatch(any(), anyString()))
                .thenAnswer(
                        invocation -> {
                            VetoCommandSender sender = invocation.getArgument(0);
                            if (sender == null) throw new AssertionError("Missing sender");
                            sender.setUser(alice);
                            return done("login");
                        });
        when(registry.complete(any(), anyString()))
                .thenAnswer(
                        invocation -> {
                            entered.countDown();
                            assertTrue(release.await(5, TimeUnit.SECONDS));
                            return List.of(new Frame.Completion("private-credential", null, null));
                        });
        var address = "tcp://127.0.0.1:" + port;
        var endpoint =
                openEndpoint(
                        registry, mock(AgentService.class), TestAgentSessions.create(), address);
        var server = endpoint.getBean(IpcServer.class);
        try (var terminal = connect(address)) {
            request(terminal, "login");
            terminal.send(new Frame.Complete("/credential", nextSequence()));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            server.revokeUser(TestUsers.ALICE);
            release.countDown();
            terminal.send(new Frame.Heartbeat());
            assertInstanceOf(Frame.HeartbeatAck.class, receive(terminal));
        } finally {
            release.countDown();
            endpoint.close();
        }
    }

    @Test
    @Timeout(20)
    void queuedOutputCannotSurviveAuthenticationChangeButCommandStillCompletes() throws Exception {
        int port;
        try (var reservation = new ServerSocket(0)) {
            port = reservation.getLocalPort();
        }
        var alice = TestUsers.registry().findByUserId(TestUsers.ALICE).orElseThrow();
        var registry = mock(CommandRegistry.class);
        var senderRef = new AtomicReference<VetoCommandSender>();
        doCallRealMethod().when(registry).terminalOutcome(any(), any());
        when(registry.dispatch(any(), anyString()))
                .thenAnswer(
                        invocation -> {
                            VetoCommandSender sender = invocation.getArgument(0);
                            if (sender == null) throw new AssertionError("Missing sender");
                            sender.setUser(alice);
                            senderRef.set(sender);
                            return done("logged in");
                        });
        var address = "tcp://127.0.0.1:" + port;
        var endpoint =
                openEndpoint(
                        registry, mock(AgentService.class), TestAgentSessions.create(), address);
        var server = endpoint.getBean(IpcServer.class);
        try (var terminal = connect(address)) {
            request(terminal, "login");
            var sender = senderRef.get();
            if (sender == null) throw new AssertionError("Missing sender");
            var connection = sender.connection();
            long original = connection.authenticationVersion();
            var privateOutput = Delta.message("private queued text");
            var privateResult =
                    new Frame.Done(
                            new Scope.GlobalScope(),
                            null,
                            -1,
                            "private result",
                            null,
                            nextSequence());
            server.revokeUser(TestUsers.ALICE);
            assertNull(connection.authorizedOutput(privateOutput, original, () -> true));
            var cancelled =
                    assertInstanceOf(
                            Frame.Done.class,
                            connection.authorizedOutput(privateResult, original, () -> true));
            assertNull(cancelled.content());
            assertNull(cancelled.username());
            assertEquals(privateResult.seq(), cancelled.seq());
            request(terminal, "login-again");
            assertNull(connection.authorizedOutput(privateOutput, original, () -> true));
            var refreshed =
                    assertInstanceOf(
                            Frame.Done.class,
                            connection.authorizedOutput(privateResult, original, () -> true));
            assertNull(refreshed.content());
            assertEquals(TestUsers.ALICE, refreshed.scope().userId());
            assertNull(
                    connection.authorizedOutput(
                            new Frame.Error(
                                    new Scope.SessionScope(
                                            TestUsers.ALICE, UUID.randomUUID().toString()),
                                    null,
                                    -1,
                                    "private broadcast",
                                    0,
                                    null,
                                    null),
                            original,
                            () -> true));
            terminal.send(new Frame.Heartbeat());
            assertInstanceOf(Frame.HeartbeatAck.class, receive(terminal));
        } finally {
            endpoint.close();
        }
    }

    @Test
    @Timeout(20)
    void retiredProducerCannotWriteToUnauthenticatedOrReplacementConnection() throws Exception {
        int port;
        try (var reservation = new ServerSocket(0)) {
            port = reservation.getLocalPort();
        }
        var alice = TestUsers.registry().findByUserId(TestUsers.ALICE).orElseThrow();
        var registry = mock(CommandRegistry.class);
        var senderRef = new AtomicReference<VetoCommandSender>();
        doCallRealMethod().when(registry).terminalOutcome(any(), any());
        when(registry.dispatch(any(), anyString()))
                .thenAnswer(
                        invocation -> {
                            VetoCommandSender sender = invocation.getArgument(0);
                            if (sender == null) throw new AssertionError("Missing sender");
                            sender.setUser(alice);
                            senderRef.set(sender);
                            return done("logged in");
                        });
        var address = "tcp://127.0.0.1:" + port;
        var endpoint =
                openEndpoint(
                        registry, mock(AgentService.class), TestAgentSessions.create(), address);
        var server = endpoint.getBean(IpcServer.class);
        try (var terminal = connect(address)) {
            request(terminal, "login");
            var sender = senderRef.get();
            if (sender == null) throw new AssertionError("Missing sender");
            terminal.send(new Frame.Bye());
            var connectionsField = IpcServer.class.getDeclaredField("connections");
            connectionsField.setAccessible(true);
            if (!(connectionsField.get(server) instanceof Map<?, ?> connections))
                throw new AssertionError("Missing connections");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!connections.isEmpty() && System.nanoTime() < deadline) Thread.sleep(1);
            assertTrue(connections.isEmpty(), "the Bye frame must retire the original connection");
            var delta =
                    new Delta(
                            new Scope.AgentScope(
                                    TestUsers.ALICE, UUID.randomUUID().toString(), "agent"),
                            new Delta.Thought("private", 1));
            assertFalse(sender.connection().sendSession(delta, () -> true));
            sender.output("retired connection text");
            assertThrows(IOException.class, terminal::receive);
            try (var replacement = connect(address)) {
                assertFalse(sender.connection().sendSession(delta, () -> true));
                sender.output("retired connection text");
                assertFalse(sender.connection().send(done("retired completion")));
                replacement.send(new Frame.Heartbeat());
                assertInstanceOf(Frame.HeartbeatAck.class, receive(replacement));
            }
        } finally {
            endpoint.close();
        }
    }

    @Test
    @Timeout(20)
    void revocationRejectsQueuedAgentOutputAndOldApprovalEvenAfterLogin() throws Exception {
        int port;
        try (var reservation = new ServerSocket(0)) {
            port = reservation.getLocalPort();
        }
        var alice = TestUsers.registry().findByUserId(TestUsers.ALICE).orElseThrow();
        var registry = mock(CommandRegistry.class);
        var agents = mock(AgentService.class);
        var senderRef = new AtomicReference<VetoCommandSender>();
        doCallRealMethod().when(registry).terminalOutcome(any(), any());
        when(registry.dispatch(any(), anyString()))
                .thenAnswer(
                        invocation -> {
                            VetoCommandSender sender = invocation.getArgument(0);
                            if (sender == null) throw new AssertionError("Missing sender");
                            sender.setUser(alice);
                            senderRef.set(sender);
                            return done("logged in");
                        });
        var runtime = runtime(agents);

        var address = "tcp://127.0.0.1:" + port;
        var endpoint = openEndpoint(registry, agents, runtime, address);
        var server = endpoint.getBean(IpcServer.class);
        var sessionId = UUID.randomUUID();
        try (var terminal = connect(address)) {
            request(terminal, "login");
            var sender = senderRef.get();
            if (sender == null) throw new AssertionError("Missing sender");
            var prompt =
                    new VetoPrompt(
                            "agent",
                            "call",
                            "tool",
                            VetoScenario.GENERIC,
                            List.of(VetoOption.ACCEPT_GENERIC),
                            Map.of(),
                            null,
                            null,
                            1);
            runtime.select(
                    sender.terminalId(),
                    new Scope.SessionScope(TestUsers.ALICE, sessionId.toString()));
            runtime.session(new Scope.SessionScope(TestUsers.ALICE, sessionId.toString()))
                    .send(
                            new Scope.AgentScope(
                                    TestUsers.ALICE, sessionId.toString(), prompt.agentId()),
                            prompt);
            var veto = assertInstanceOf(Frame.Veto.class, receive(terminal));
            var delta =
                    new Delta(
                            new Scope.AgentScope(TestUsers.ALICE, sessionId.toString(), "agent"),
                            new Delta.Thought("private", 1));
            {
                long version = sender.connection().authenticationVersion();
                server.revokeUser(TestUsers.ALICE);
                assertNull(sender.connection().authorizedOutput(delta, version, () -> true));
                publish(runtime, delta);
                runtime.session(new Scope.SessionScope(TestUsers.ALICE, sessionId.toString()))
                        .send(
                                new Scope.AgentScope(
                                        TestUsers.ALICE, sessionId.toString(), prompt.agentId()),
                                prompt);
                var reply =
                        new Frame.VetoResult(
                                veto.scope(), veto.callId(), "ACCEPT_GENERIC", veto.seq());
                terminal.send(reply);
                assertInstanceOf(Frame.Error.class, receive(terminal));
                terminal.send(new Frame.Heartbeat());
                assertInstanceOf(Frame.HeartbeatAck.class, receive(terminal));
                verify(agents, never())
                        .resolveVeto(anyString(), anyString(), anyString(), anyLong());
                request(terminal, "login-again");
                terminal.send(reply);
                assertInstanceOf(Frame.Error.class, receive(terminal));
                terminal.send(new Frame.Heartbeat());
                assertInstanceOf(Frame.HeartbeatAck.class, receive(terminal));
                verify(agents, never())
                        .resolveVeto(anyString(), anyString(), anyString(), anyLong());
                publishApproval(
                        endpoint,
                        sender,
                        TestUsers.ALICE,
                        sessionId,
                        new VetoPrompt(
                                "agent",
                                "call",
                                "tool",
                                VetoScenario.GENERIC,
                                List.of(VetoOption.ACCEPT_GENERIC),
                                Map.of(),
                                null,
                                null,
                                2));
                var fresh = assertInstanceOf(Frame.Veto.class, receive(terminal));
                terminal.send(
                        new Frame.VetoResult(
                                fresh.scope(), fresh.callId(), "ACCEPT_GENERIC", fresh.seq()));
                terminal.send(new Frame.Heartbeat());
                assertInstanceOf(Frame.HeartbeatAck.class, receive(terminal));
                verify(agents, timeout(2_000)).resolveVeto("agent", "call", "ACCEPT_GENERIC", 2);
                publish(runtime, delta);
                assertInstanceOf(Delta.class, receive(terminal));
            }
        } finally {
            endpoint.close();
        }
    }

    @Test
    @Timeout(20)
    void idleTerminalReceivesOnlyItsSelectedSessionsPluginActions() throws Exception {
        int port;
        try (var reservation = new ServerSocket(0)) {
            port = reservation.getLocalPort();
        }
        var registry = mock(CommandRegistry.class);
        doCallRealMethod().when(registry).terminalOutcome(any(), any());
        var alice = TestUsers.registry().findByUserId(TestUsers.ALICE).orElseThrow();
        var selected = UUID.randomUUID().toString();
        var senderRef = new AtomicReference<VetoCommandSender>();

        when(registry.dispatch(any(), anyString()))
                .thenAnswer(
                        invocation -> {
                            VetoCommandSender sender = invocation.getArgument(0);
                            if (sender == null) throw new AssertionError("Missing sender");
                            sender.setUser(alice);
                            senderRef.set(sender);
                            return done("logged in");
                        });
        var runtime = TestAgentSessions.create();

        var address = "tcp://127.0.0.1:" + port;
        var endpoint = openEndpoint(registry, mock(AgentService.class), runtime, address);
        var server = endpoint.getBean(IpcServer.class);
        try (var terminal = connect(address)) {
            request(terminal, "login");
            var sender = senderRef.get();
            if (sender == null) throw new AssertionError("Missing sender");
            runtime.select(sender.terminalId(), new Scope.SessionScope(TestUsers.ALICE, selected));
            var data = JsonNodeFactory.instance.objectNode().put("status", "ready");
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            publish(
                                    runtime,
                                    new Frame.PluginAction(
                                            new Scope.SessionScope(TestUsers.BOB, selected),
                                            "other-owner",
                                            data)));
            publish(
                    runtime,
                    new Frame.PluginAction(
                            new Scope.SessionScope(TestUsers.ALICE, UUID.randomUUID().toString()),
                            "other-session",
                            data));
            var expected =
                    new Frame.PluginAction(
                            new Scope.SessionScope(TestUsers.ALICE, selected), "task", data);
            publish(runtime, expected);
            assertEquals(expected, receive(terminal));
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            publish(
                                    runtime,
                                    new Frame.Action(
                                            new Scope.SessionScope(TestUsers.BOB, selected),
                                            List.of("agents"))));
            publish(
                    runtime,
                    new Frame.Action(
                            new Scope.SessionScope(TestUsers.ALICE, UUID.randomUUID().toString()),
                            List.of("agents")));
            var action =
                    new Frame.Action(
                            new Scope.SessionScope(TestUsers.ALICE, selected), List.of("agents"));
            publish(runtime, action);
            assertEquals(action, receive(terminal));
            server.revokeUser(TestUsers.ALICE);
            publish(runtime, expected);
            terminal.send(new Frame.Heartbeat());
            assertInstanceOf(Frame.HeartbeatAck.class, receive(terminal));
        } finally {
            endpoint.close();
        }
    }

    @Test
    @Timeout(20)
    void revocationCancelsPendingInputAndTerminalCanLoginAgain() throws Exception {
        int port;
        try (var reservation = new ServerSocket(0)) {
            port = reservation.getLocalPort();
        }
        String address = "tcp://127.0.0.1:" + port;
        var alice = TestUsers.registry().findByUserId(TestUsers.ALICE).orElseThrow();
        var registry = mock(CommandRegistry.class);
        doCallRealMethod().when(registry).terminalOutcome(any(), any());
        when(registry.dispatch(any(), anyString()))
                .thenAnswer(
                        invocation -> {
                            VetoCommandSender sender = invocation.getArgument(0);
                            if (sender == null) throw new AssertionError("Missing command sender");
                            String raw = invocation.getArgument(1);
                            if ("login".equals(raw)) sender.setUser(alice);
                            else if ("prompt".equals(raw))
                                assertNull(sender.input("Confirm action", false));
                            return done(Boolean.toString(sender.isLoggedIn()));
                        });
        var endpoint =
                openEndpoint(
                        registry, mock(AgentService.class), TestAgentSessions.create(), address);
        var server = endpoint.getBean(IpcServer.class);
        try (var terminal = connect(address)) {
            assertEquals("true", request(terminal, "login").content());
            terminal.send(requestFrame("prompt"));
            assertInstanceOf(Frame.Prompt.class, receive(terminal));
            server.revokeUser(TestUsers.ALICE);
            var revoked = assertInstanceOf(Frame.Done.class, receive(terminal));

            assertNull(revoked.content());
            assertEquals("true", request(terminal, "login").content());
        } finally {
            endpoint.close();
        }
    }

    @Test
    @Timeout(20)
    void logoutRevokesConnectedTerminalsAndQueuedRequestIdentity() throws Exception {
        int port;
        try (var reservation = new ServerSocket(0)) {
            port = reservation.getLocalPort();
        }
        String address = "tcp://127.0.0.1:" + port;
        var users = TestUsers.registry();
        var alice = users.findByUserId(TestUsers.ALICE).orElseThrow();
        var bob = users.findByUserId(TestUsers.BOB).orElseThrow();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var registry = mock(CommandRegistry.class);
        doCallRealMethod().when(registry).terminalOutcome(any(), any());
        when(registry.dispatch(any(), anyString()))
                .thenAnswer(
                        invocation -> {
                            VetoCommandSender sender = invocation.getArgument(0);
                            if (sender == null) throw new AssertionError("Missing command sender");
                            String raw = invocation.getArgument(1);
                            if ("login-alice".equals(raw)) sender.setUser(alice);
                            else if ("login-bob".equals(raw)) sender.setUser(bob);
                            else if ("hold".equals(raw)) {
                                assertEquals(TestUsers.ALICE, CurrentUser.id());
                                entered.countDown();
                                assertTrue(release.await(5, TimeUnit.SECONDS));
                            } else {
                                assertEquals(sender.userId(), CurrentUser.id());
                            }
                            return done(Boolean.toString(sender.isLoggedIn()));
                        });
        when(registry.complete(any(), anyString())).thenReturn(List.of());
        var endpoint =
                openEndpoint(
                        registry, mock(AgentService.class), TestAgentSessions.create(), address);
        var server = endpoint.getBean(IpcServer.class);
        var beans = new StaticListableBeanFactory(Map.of("ipcServer", server));
        var lifecycle =
                new AuthLifecycleManager(
                        mock(KeysteadVault.class),
                        mock(PromptHandler.class),
                        mock(EventManager.class),
                        new LoginSessionManager(),
                        beans.getBeanProvider(IpcServer.class));
        try (var first = connect(address);
                var second = connect(address);
                var other = connect(address)) {
            request(first, "login-alice");
            request(second, "login-alice");
            request(other, "login-bob");
            first.send(requestFrame("hold"));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            first.send(requestFrame("queued"));
            // Completion is ordered behind the queued frame on the session mailbox.
            first.send(new Frame.Complete("barrier", nextSequence()));
            assertInstanceOf(Frame.CompleteResult.class, receive(first));
            lifecycle.logout(TestUsers.ALICE);
            release.countDown();
            assertInstanceOf(Frame.Done.class, receive(first));
            var queued = assertInstanceOf(Frame.Done.class, receive(first));
            assertEquals("false", queued.content());
            assertEquals("false", request(second, "probe").content());
            assertEquals("true", request(other, "probe").content());
        } finally {
            release.countDown();
            endpoint.close();
        }
    }

    private static @NonNull TcpConnection connect(@NonNull String address) throws IOException {
        var connection = TcpConnection.connect(address);
        connection.send(new Frame.Hello(Frame.PROTOCOL_VERSION, 1, Version.UNKNOWN, "."));
        assertInstanceOf(Frame.Welcome.class, receive(connection));
        return connection;
    }

    private static Frame.@NonNull Done request(
            @NonNull TcpConnection connection, @NonNull String raw) throws IOException {
        var request = requestFrame(raw);
        connection.send(request);
        var result = assertInstanceOf(Frame.Done.class, receive(connection));
        assertEquals(request.seq(), result.seq());
        return result;
    }

    @Test
    @Timeout(15)
    void rejectedFramesCloseOnlyTheirConnectionAndCommandFailureAllowsTheNextRequest()
            throws Exception {
        var registry = mock(CommandRegistry.class);
        doCallRealMethod().when(registry).terminalOutcome(any(), any());
        when(registry.complete(any(), eq("explode")))
                .thenThrow(new IllegalArgumentException("invalid completion syntax"));
        when(registry.complete(any(), eq("healthy"))).thenReturn(List.of());
        try (var endpoint =
                openEndpoint(
                        registry,
                        mock(AgentService.class),
                        TestAgentSessions.create(),
                        "tcp://127.0.0.1:0")) {
            var address = "tcp://127.0.0.1:" + endpoint.getBean(ServerSocket.class).getLocalPort();
            try (var uninitialized = TcpConnection.connect(address)) {
                uninitialized.send(new Frame.Heartbeat());
                assertInstanceOf(Frame.Error.class, receive(uninitialized));
                assertThrows(IOException.class, uninitialized::receive);
            }
            try (var malformed = connect(address)) {
                malformed.send("{malformed json".getBytes(StandardCharsets.UTF_8));
                assertThrows(IOException.class, malformed::receive);
            }
            try (var healthy = connect(address)) {
                healthy.send(new Frame.Complete("explode", 2));
                var rejected = receive(healthy);
                assertTrue(rejected instanceof Frame.Error error && error.seq() == 2);
                healthy.send(new Frame.Complete("healthy", 3));
                var result = receive(healthy);
                assertTrue(
                        result instanceof Frame.CompleteResult completed && completed.seq() == 3);
                verify(registry).complete(any(), eq("healthy"));
            }
        }
    }

    @Test
    @Timeout(15)
    void slowPeerOutputIsBoundedAndDoesNotDelayHealthyPeer() throws Exception {
        var registry = mock(CommandRegistry.class);
        var flooding = new CountDownLatch(1);
        var rejected = new CountDownLatch(1);
        var payload = Delta.message("x".repeat(512 * 1024));
        doCallRealMethod().when(registry).terminalOutcome(any(), any());
        when(registry.dispatch(any(), anyString()))
                .thenAnswer(
                        invocation -> {
                            VetoCommandSender sender = invocation.getArgument(0);
                            if (sender == null) throw new AssertionError("Missing sender");
                            flooding.countDown();
                            for (int message = 0; message < 128; message++) {
                                if (!sender.connection().send(payload)) {
                                    rejected.countDown();
                                    break;
                                }
                            }
                            return done(null);
                        });
        try (var endpoint =
                openEndpoint(
                        registry,
                        mock(AgentService.class),
                        TestAgentSessions.create(),
                        "tcp://127.0.0.1:0")) {
            int port = endpoint.getBean(ServerSocket.class).getLocalPort();
            try (var socket = new Socket();
                    var healthy = connect("tcp://127.0.0.1:" + port)) {
                socket.setReceiveBufferSize(1024);
                socket.connect(new InetSocketAddress("127.0.0.1", port));
                try (var slow = TcpConnection.accept(socket)) {
                    slow.send(new Frame.Hello(Frame.PROTOCOL_VERSION, 1, Version.UNKNOWN, "."));
                    assertInstanceOf(Frame.Welcome.class, slow.receive());
                    slow.send(requestFrame("flood"));
                    assertTrue(flooding.await(2, TimeUnit.SECONDS));
                    healthy.send(new Frame.Heartbeat());
                    assertInstanceOf(Frame.HeartbeatAck.class, healthy.receive());
                    assertTrue(
                            rejected.await(5, TimeUnit.SECONDS),
                            "slow output must hit the byte budget");
                    healthy.send(new Frame.Heartbeat());
                    assertInstanceOf(Frame.HeartbeatAck.class, healthy.receive());
                }
            }
        }
    }

    @Test
    @Timeout(15)
    void shutdownJoinsTheAcceptorBeforeReleasingTheEndpoint() throws Exception {
        int port;
        try (var reservation = new ServerSocket(0)) {
            port = reservation.getLocalPort();
        }
        String address = "tcp://127.0.0.1:" + port;
        var registry = mock(CommandRegistry.class);
        doCallRealMethod().when(registry).terminalOutcome(any(), any());
        var endpoint =
                openEndpoint(
                        registry, mock(AgentService.class), TestAgentSessions.create(), address);
        var server = endpoint.getBean(IpcServer.class);
        try (var terminal = connect(address)) {
            var workerField = IpcServer.class.getDeclaredField("acceptor");
            workerField.setAccessible(true);
            if (!(workerField.get(server) instanceof Thread worker))
                throw new AssertionError("Missing IO owner");
            assertTrue(worker.isVirtual());
            endpoint.close();
            assertThrows(IOException.class, terminal::receive);
            assertFalse(worker.isAlive(), "shutdown must leave no IO worker or bound socket");
            var replacement =
                    openEndpoint(
                            registry,
                            mock(AgentService.class),
                            TestAgentSessions.create(),
                            address);
            replacement.close();
        } finally {
            endpoint.close();
        }
    }

    @Test
    @Timeout(15)
    void cancellationWaitsForActualExitWhileControlFramesRemainResponsive() throws Exception {
        int port;
        try (var reservation = new ServerSocket(0)) {
            port = reservation.getLocalPort();
        }
        String address = "tcp://127.0.0.1:" + port;
        var entered = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var nextEntered = new CountDownLatch(1);
        var registry = mock(CommandRegistry.class);
        doCallRealMethod().when(registry).terminalOutcome(any(), any());
        when(registry.dispatch(any(), anyString()))
                .thenAnswer(
                        invocation -> {
                            String raw = invocation.getArgument(1);
                            if ("hold".equals(raw)) {
                                entered.countDown();
                                boolean done = false;
                                while (!done) {
                                    try {
                                        done = release.await(5, TimeUnit.SECONDS);
                                        if (!done)
                                            throw new AssertionError(
                                                    "test did not release command");
                                    } catch (InterruptedException ignored) {
                                        interrupted.countDown();
                                    }
                                }
                            } else nextEntered.countDown();
                            return done(raw);
                        });
        when(registry.complete(any(), anyString())).thenReturn(List.of());
        var endpoint =
                openEndpoint(
                        registry, mock(AgentService.class), TestAgentSessions.create(), address);
        try (var terminal = connect(address)) {
            var held = requestFrame("hold");
            terminal.send(held);
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            long cancelSeq = nextSequence();
            terminal.send(new Frame.Cancel(cancelSeq, held.seq()));
            assertTrue(interrupted.await(2, TimeUnit.SECONDS));
            assertEquals(
                    new Frame.CancelResult(Frame.CancelStatus.ACCEPTED, cancelSeq),
                    receive(terminal));
            terminal.send(requestFrame("next"));
            terminal.send(new Frame.Complete("barrier", nextSequence()));
            assertInstanceOf(Frame.CompleteResult.class, receive(terminal));
            assertEquals(
                    1,
                    nextEntered.getCount(),
                    "replacement body must wait for cancelled body exit");
            long repeatSeq = nextSequence();
            terminal.send(new Frame.Cancel(repeatSeq, held.seq()));
            assertEquals(
                    new Frame.CancelResult(Frame.CancelStatus.ACCEPTED, repeatSeq),
                    receive(terminal));
            terminal.send(new Frame.Heartbeat());
            assertInstanceOf(Frame.HeartbeatAck.class, receive(terminal));
            release.countDown();
            var cancelled = assertInstanceOf(Frame.Done.class, receive(terminal));
            assertNull(cancelled.content());
            var completed = assertInstanceOf(Frame.Done.class, receive(terminal));
            assertEquals("next", completed.content());
            assertTrue(nextEntered.await(1, TimeUnit.SECONDS));
            long finishedSeq = nextSequence();
            terminal.send(new Frame.Cancel(finishedSeq, held.seq()));
            assertEquals(
                    new Frame.CancelResult(Frame.CancelStatus.NO_ACTIVE_REQUEST, finishedSeq),
                    receive(terminal));
            terminal.send(new Frame.Heartbeat());
            assertInstanceOf(Frame.HeartbeatAck.class, receive(terminal));
        } finally {
            release.countDown();
            endpoint.close();
        }
    }

    @Test
    @Timeout(15)
    void promptCancellationAcknowledgesDismissalBeforeTheCommandFinishes() throws Exception {
        int port;
        try (var reservation = new ServerSocket(0)) {
            port = reservation.getLocalPort();
        }
        var dismissed = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var registry = mock(CommandRegistry.class);
        doCallRealMethod().when(registry).terminalOutcome(any(), any());
        when(registry.dispatch(any(), anyString()))
                .thenAnswer(
                        invocation -> {
                            VetoCommandSender sender = invocation.getArgument(0);
                            if (sender == null) throw new AssertionError("Missing sender");
                            assertNull(sender.input("Name", false));
                            dismissed.countDown();
                            assertTrue(release.await(5, TimeUnit.SECONDS));
                            return done("prompt dismissed");
                        });
        var address = "tcp://127.0.0.1:" + port;
        var endpoint =
                openEndpoint(
                        registry, mock(AgentService.class), TestAgentSessions.create(), address);
        try (var terminal = connect(address)) {
            terminal.send(requestFrame("prompt"));
            var prompt = assertInstanceOf(Frame.Prompt.class, receive(terminal));
            long cancelSeq = nextSequence();
            terminal.send(new Frame.Cancel(cancelSeq, prompt.seq()));
            assertEquals(
                    new Frame.CancelResult(Frame.CancelStatus.ACCEPTED, cancelSeq),
                    receive(terminal));
            assertTrue(dismissed.await(1, TimeUnit.SECONDS));
            terminal.send(new Frame.Heartbeat());
            assertInstanceOf(Frame.HeartbeatAck.class, receive(terminal));
            release.countDown();
            assertEquals(
                    "prompt dismissed",
                    assertInstanceOf(Frame.Done.class, receive(terminal)).content());
        } finally {
            release.countDown();
            endpoint.close();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"accepted", "rejected", "exception"})
    @Timeout(15)
    void exactApprovalRefusalDoesNotCancelAnUnrelatedCommand(@NonNull String outcome)
            throws Exception {
        int port;
        try (var reservation = new ServerSocket(0)) {
            port = reservation.getLocalPort();
        }
        var alice = TestUsers.registry().findByUserId(TestUsers.ALICE).orElseThrow();
        var registry = mock(CommandRegistry.class);
        var agents = mock(AgentService.class);
        if (outcome.equals("exception")) {
            when(agents.resolveVeto("agent", "call", "GENERIC_DECLINE", 1))
                    .thenThrow(new IllegalStateException("decline failed"));
        } else {
            when(agents.resolveVeto("agent", "call", "GENERIC_DECLINE", 1))
                    .thenReturn(outcome.equals("accepted"));
        }
        var senderRef = new AtomicReference<VetoCommandSender>();
        doCallRealMethod().when(registry).terminalOutcome(any(), any());
        when(registry.dispatch(any(), anyString()))
                .thenAnswer(
                        invocation -> {
                            VetoCommandSender sender = invocation.getArgument(0);
                            if (sender == null) throw new AssertionError("Missing sender");
                            sender.setUser(alice);
                            senderRef.set(sender);
                            return done("logged in");
                        });
        var address = "tcp://127.0.0.1:" + port;
        var endpoint = openEndpoint(registry, agents, runtime(agents), address);
        try (var terminal = connect(address)) {
            request(terminal, "login");
            var sender = senderRef.get();
            if (sender == null) throw new AssertionError("Missing sender");
            publishApproval(
                    endpoint,
                    sender,
                    TestUsers.ALICE,
                    UUID.randomUUID(),
                    new VetoPrompt(
                            "agent",
                            "call",
                            "tool",
                            VetoScenario.GENERIC,
                            List.of(VetoOption.ACCEPT_GENERIC, VetoOption.GENERIC_DECLINE),
                            Map.of(),
                            null,
                            null,
                            1));
            var veto = assertInstanceOf(Frame.Veto.class, receive(terminal));
            long cancelSeq = nextSequence();
            terminal.send(new Frame.Cancel(cancelSeq, cancelSeq - 1));
            assertEquals(
                    new Frame.CancelResult(Frame.CancelStatus.NO_ACTIVE_REQUEST, cancelSeq),
                    receive(terminal));
            verify(agents, never()).resolveVeto(anyString(), anyString(), anyString(), anyLong());
            terminal.send(
                    new Frame.VetoResult(
                            veto.scope(), veto.callId(), "GENERIC_DECLINE", veto.seq()));
            verify(agents, timeout(2_000)).resolveVeto("agent", "call", "GENERIC_DECLINE", 1);
            if (!outcome.equals("accepted")) assertInstanceOf(Frame.Error.class, receive(terminal));
            terminal.send(new Frame.Heartbeat());
            assertInstanceOf(Frame.HeartbeatAck.class, receive(terminal));
        } finally {
            endpoint.close();
        }
    }

    private static @NonNull AgentSessions runtime(@NonNull AgentService agents) {
        when(agents.hasApprovalAgent(anyString(), anyString())).thenReturn(true);
        when(agents.resolveVeto(anyString(), anyString(), eq("ACCEPT_GENERIC"), anyLong()))
                .thenReturn(true);
        var repository = mock(SessionRepository.class);
        when(repository.findById(anyString()))
                .thenReturn(Optional.of(new SessionEntity(TestUsers.ALICE, "owned")));
        return TestAgentSessions.create(repository, agents);
    }

    private static void publishApproval(
            @NonNull AnnotationConfigApplicationContext endpoint,
            @NonNull VetoCommandSender sender,
            @NonNull UUID user,
            @NonNull UUID sessionId,
            @NonNull VetoPrompt prompt) {
        var runtime = endpoint.getBean(AgentSessions.class);
        var scope = new Scope.SessionScope(user, sessionId.toString());
        runtime.select(sender.terminalId(), scope);
        runtime.session(scope)
                .send(new Scope.AgentScope(user, sessionId.toString(), prompt.agentId()), prompt);
    }

    private static @NonNull Frame receive(@NonNull TcpConnection connection) throws IOException {
        Frame frame;
        do {
            frame = receiveRaw(connection);
        } while (frame instanceof Frame.RequestAccepted);
        return frame;
    }

    private static @NonNull Frame receiveRaw(@NonNull TcpConnection connection) throws IOException {
        return connection.receive();
    }
}
