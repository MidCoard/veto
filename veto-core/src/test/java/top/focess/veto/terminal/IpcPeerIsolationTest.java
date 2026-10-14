package top.focess.veto.terminal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.net.ServerSocket;
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
import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;
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
import top.focess.veto.contract.FrameCodec;
import top.focess.veto.contract.Version;
import top.focess.veto.event.EventManager;
import top.focess.veto.model.SessionEntity;
import top.focess.veto.model.SessionRepository;
import top.focess.veto.session.AgentSessions;
import top.focess.veto.session.SessionService;
import top.focess.veto.session.TestAgentSessions;
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
                                "terminal-test", Map.of("veto.terminal.bind-address", address)));
        context.registerBean(CommandRegistry.class, () -> registry);
        context.registerBean(AgentService.class, () -> agents);
        context.registerBean(AgentSessions.class, () -> runtime);
        context.registerBean(SessionService.class, () -> mock(SessionService.class));
        context.registerBean(SessionCommandService.class, () -> mock(SessionCommandService.class));
        context.register(TerminalConfiguration.class);
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
        try (var context = new ZContext()) {
            var terminal = connect(context, address, "revoked-command");
            assertEquals("fresh login", request(terminal, "login").content());
            assertTrue(terminal.send(FrameCodec.encode(requestFrame("private"))));
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
            assertTrue(terminal.send(FrameCodec.encode(requestFrame("fresh"))));
            var output = assertInstanceOf(Delta.class, receive(terminal));
            assertEquals(
                    "fresh command output",
                    assertInstanceOf(Delta.Message.class, output.data()).text());
            var fresh = assertInstanceOf(Frame.Done.class, receive(terminal));

            assertEquals("fresh command complete", fresh.content());
            assertTrue(terminal.send(FrameCodec.encode(requestFrame("logout"))));
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
        try (var context = new ZContext()) {
            var terminal = connect(context, address, "revoked-completion");
            request(terminal, "login");
            assertTrue(
                    terminal.send(
                            FrameCodec.encode(new Frame.Complete("/credential", nextSequence()))));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            server.revokeUser(TestUsers.ALICE);
            release.countDown();
            assertTrue(terminal.send(FrameCodec.encode(new Frame.Heartbeat())));
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
        try (var context = new ZContext()) {
            var terminal = connect(context, address, "auth-incarnation");
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
            assertTrue(terminal.send(FrameCodec.encode(new Frame.Heartbeat())));
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
        try (var context = new ZContext()) {
            var terminal = connect(context, address, "reused-routing-identity");
            request(terminal, "login");
            var sender = senderRef.get();
            if (sender == null) throw new AssertionError("Missing sender");
            assertTrue(terminal.send(FrameCodec.encode(new Frame.Bye())));
            var sessionsField = IpcServer.class.getDeclaredField("peers");
            sessionsField.setAccessible(true);
            if (!(sessionsField.get(server) instanceof Map<?, ?> sessions))
                throw new AssertionError("Missing sessions");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!sessions.isEmpty() && System.nanoTime() < deadline) Thread.sleep(1);
            assertTrue(sessions.isEmpty(), "the Bye frame must retire the original session");
            var delta =
                    new Delta(
                            new Scope.AgentScope(
                                    TestUsers.ALICE, UUID.randomUUID().toString(), "agent"),
                            new Delta.Thought("private", 1));
            assertFalse(sender.connection().sendSession(delta, () -> true));
            sender.output("retired connection text");
            assertTrue(terminal.send(FrameCodec.encode(new Frame.Heartbeat())));
            assertInstanceOf(Frame.Terminate.class, receive(terminal));
            assertTrue(
                    terminal.send(
                            FrameCodec.encode(
                                    new Frame.Hello(
                                            Frame.PROTOCOL_VERSION, 2, Version.UNKNOWN, "."))));
            assertInstanceOf(Frame.Welcome.class, receive(terminal));
            assertFalse(sender.connection().sendSession(delta, () -> true));
            sender.output("retired connection text");
            assertFalse(sender.connection().send(done("retired completion")));
            assertTrue(terminal.send(FrameCodec.encode(new Frame.Heartbeat())));
            assertInstanceOf(Frame.HeartbeatAck.class, receive(terminal));
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
        try (var context = new ZContext()) {
            var terminal = connect(context, address, "revoked-approval");
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
                assertTrue(terminal.send(FrameCodec.encode(reply)));
                assertInstanceOf(Frame.Error.class, receive(terminal));
                assertTrue(terminal.send(FrameCodec.encode(new Frame.Heartbeat())));
                assertInstanceOf(Frame.HeartbeatAck.class, receive(terminal));
                verify(agents, never())
                        .resolveVeto(anyString(), anyString(), anyString(), anyLong());
                request(terminal, "login-again");
                assertTrue(terminal.send(FrameCodec.encode(reply)));
                assertInstanceOf(Frame.Error.class, receive(terminal));
                assertTrue(terminal.send(FrameCodec.encode(new Frame.Heartbeat())));
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
                assertTrue(
                        terminal.send(
                                FrameCodec.encode(
                                        new Frame.VetoResult(
                                                fresh.scope(),
                                                fresh.callId(),
                                                "ACCEPT_GENERIC",
                                                fresh.seq()))));
                assertTrue(terminal.send(FrameCodec.encode(new Frame.Heartbeat())));
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

        when(registry.dispatch(any(), anyString()))
                .thenAnswer(
                        invocation -> {
                            VetoCommandSender sender = invocation.getArgument(0);
                            if (sender == null) throw new AssertionError("Missing sender");
                            sender.setUser(alice);
                            return done("logged in");
                        });
        var runtime = TestAgentSessions.create();

        var address = "tcp://127.0.0.1:" + port;
        var endpoint = openEndpoint(registry, mock(AgentService.class), runtime, address);
        var server = endpoint.getBean(IpcServer.class);
        try (var context = new ZContext()) {
            var terminal = connect(context, address, "plugin-peer");
            request(terminal, "login");
            runtime.select("plugin-peer", new Scope.SessionScope(TestUsers.ALICE, selected));
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
            assertTrue(terminal.send(FrameCodec.encode(new Frame.Heartbeat())));
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
        try (var context = new ZContext()) {
            var terminal = connect(context, address, "pending-input");
            assertEquals("true", request(terminal, "login").content());
            assertTrue(terminal.send(FrameCodec.encode(requestFrame("prompt"))));
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
        try (var context = new ZContext()) {
            var first = connect(context, address, "alice-1");
            var second = connect(context, address, "alice-2");
            var other = connect(context, address, "bob");
            request(first, "login-alice");
            request(second, "login-alice");
            request(other, "login-bob");
            assertTrue(first.send(FrameCodec.encode(requestFrame("hold"))));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertTrue(first.send(FrameCodec.encode(requestFrame("queued"))));
            // Completion is ordered behind the queued frame on the session mailbox.
            assertTrue(
                    first.send(FrameCodec.encode(new Frame.Complete("barrier", nextSequence()))));
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

    private static ZMQ.@NonNull Socket connect(
            @NonNull ZContext context, @NonNull String address, @NonNull String identity) {
        var dealer = context.createSocket(SocketType.DEALER);
        if (dealer == null) throw new AssertionError("DEALER creation failed");
        dealer.setIdentity(identity.getBytes(StandardCharsets.UTF_8));
        dealer.setReceiveTimeOut(3_000);
        dealer.connect(address);
        assertTrue(
                dealer.send(
                        FrameCodec.encodeString(
                                new Frame.Hello(Frame.PROTOCOL_VERSION, 1, Version.UNKNOWN, "."))));
        assertInstanceOf(Frame.Welcome.class, receive(dealer));
        return dealer;
    }

    private static Frame.@NonNull Done request(ZMQ.@NonNull Socket dealer, @NonNull String raw) {
        var request = requestFrame(raw);
        assertTrue(dealer.send(FrameCodec.encode(request)));
        var result = assertInstanceOf(Frame.Done.class, receive(dealer));
        assertEquals(request.seq(), result.seq());
        return result;
    }

    @Test
    @Timeout(15)
    void shortIdentitiesAndRejectedFramesDoNotKillTheServerOrSessionWorker() throws Exception {
        int port;
        try (var reservation = new ServerSocket(0)) {
            port = reservation.getLocalPort();
        }
        String address = "tcp://127.0.0.1:" + port;
        var registry = mock(CommandRegistry.class);
        doCallRealMethod().when(registry).terminalOutcome(any(), any());
        when(registry.complete(any(), eq("explode")))
                .thenThrow(new IllegalArgumentException("invalid completion syntax"));
        when(registry.complete(any(), eq("healthy"))).thenReturn(List.of());
        var endpoint =
                openEndpoint(
                        registry, mock(AgentService.class), TestAgentSessions.create(), address);
        try (var context = new ZContext()) {
            var dealer = context.createSocket(SocketType.DEALER);
            if (dealer == null) throw new AssertionError("DEALER creation failed");
            dealer.setIdentity("x".getBytes(StandardCharsets.UTF_8));
            dealer.setReceiveTimeOut(3_000);
            dealer.connect(address);
            assertTrue(dealer.send("{\"type\":\"heartbeat\"}"));
            assertInstanceOf(Frame.Terminate.class, receive(dealer));
            assertTrue(
                    dealer.send(
                            FrameCodec.encodeString(
                                    new Frame.Hello(
                                            Frame.PROTOCOL_VERSION, 1, Version.UNKNOWN, "."))));
            assertInstanceOf(Frame.Welcome.class, receive(dealer));
            assertTrue(dealer.send("{\"type\":\"heartbeat\"}"));
            assertInstanceOf(Frame.HeartbeatAck.class, receive(dealer));
            assertTrue(dealer.send("{malformed json"));
            assertTrue(dealer.send("{\"type\":\"complete\",\"raw\":\"explode\",\"seq\":2}"));
            var rejected = receive(dealer);
            assertTrue(rejected instanceof Frame.Error error && error.seq() == 2);
            assertTrue(dealer.send("{\"type\":\"complete\",\"raw\":\"healthy\",\"seq\":3}"));
            var healthy = receive(dealer);
            assertTrue(healthy instanceof Frame.CompleteResult result && result.seq() == 3);
            verify(registry).complete(any(), eq("healthy"));
        } finally {
            endpoint.close();
        }
    }

    @Test
    @Timeout(15)
    void shutdownJoinsTheIoWorkerBeforeReleasingTheEndpoint() throws Exception {
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
        try (var context = new ZContext()) {
            var terminal = connect(context, address, "shutdown-peer");
            var workerField = IpcServer.class.getDeclaredField("ioThread");
            workerField.setAccessible(true);
            if (!(workerField.get(server) instanceof Thread worker))
                throw new AssertionError("Missing IO owner");
            assertTrue(worker.isVirtual());
            endpoint.close();
            assertInstanceOf(Frame.Terminate.class, receive(terminal));
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
        try (var context = new ZContext()) {
            var terminal = connect(context, address, "cancellation-peer");
            var held = requestFrame("hold");
            assertTrue(terminal.send(FrameCodec.encode(held)));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            long cancelSeq = nextSequence();
            assertTrue(terminal.send(FrameCodec.encode(new Frame.Cancel(cancelSeq, held.seq()))));
            assertTrue(interrupted.await(2, TimeUnit.SECONDS));
            assertEquals(
                    new Frame.CancelResult(Frame.CancelStatus.ACCEPTED, cancelSeq),
                    receive(terminal));
            assertTrue(terminal.send(FrameCodec.encode(requestFrame("next"))));
            assertTrue(
                    terminal.send(
                            FrameCodec.encode(new Frame.Complete("barrier", nextSequence()))));
            assertInstanceOf(Frame.CompleteResult.class, receive(terminal));
            assertEquals(
                    1,
                    nextEntered.getCount(),
                    "replacement body must wait for cancelled body exit");
            long repeatSeq = nextSequence();
            assertTrue(terminal.send(FrameCodec.encode(new Frame.Cancel(repeatSeq, held.seq()))));
            assertEquals(
                    new Frame.CancelResult(Frame.CancelStatus.ACCEPTED, repeatSeq),
                    receive(terminal));
            assertTrue(terminal.send(FrameCodec.encode(new Frame.Heartbeat())));
            assertInstanceOf(Frame.HeartbeatAck.class, receive(terminal));
            release.countDown();
            var cancelled = assertInstanceOf(Frame.Done.class, receive(terminal));
            assertNull(cancelled.content());
            var completed = assertInstanceOf(Frame.Done.class, receive(terminal));
            assertEquals("next", completed.content());
            assertTrue(nextEntered.await(1, TimeUnit.SECONDS));
            long finishedSeq = nextSequence();
            assertTrue(terminal.send(FrameCodec.encode(new Frame.Cancel(finishedSeq, held.seq()))));
            assertEquals(
                    new Frame.CancelResult(Frame.CancelStatus.NO_ACTIVE_REQUEST, finishedSeq),
                    receive(terminal));
            assertTrue(terminal.send(FrameCodec.encode(new Frame.Heartbeat())));
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
        try (var context = new ZContext()) {
            var terminal = connect(context, address, "cancel-prompt");
            assertTrue(terminal.send(FrameCodec.encode(requestFrame("prompt"))));
            var prompt = assertInstanceOf(Frame.Prompt.class, receive(terminal));
            long cancelSeq = nextSequence();
            assertTrue(terminal.send(FrameCodec.encode(new Frame.Cancel(cancelSeq, prompt.seq()))));
            assertEquals(
                    new Frame.CancelResult(Frame.CancelStatus.ACCEPTED, cancelSeq),
                    receive(terminal));
            assertTrue(dismissed.await(1, TimeUnit.SECONDS));
            assertTrue(terminal.send(FrameCodec.encode(new Frame.Heartbeat())));
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
        try (var context = new ZContext()) {
            var terminal = connect(context, address, "cancel-approval");
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
            assertTrue(
                    terminal.send(FrameCodec.encode(new Frame.Cancel(cancelSeq, cancelSeq - 1))));
            assertEquals(
                    new Frame.CancelResult(Frame.CancelStatus.NO_ACTIVE_REQUEST, cancelSeq),
                    receive(terminal));
            verify(agents, never()).resolveVeto(anyString(), anyString(), anyString(), anyLong());
            assertTrue(
                    terminal.send(
                            FrameCodec.encode(
                                    new Frame.VetoResult(
                                            veto.scope(),
                                            veto.callId(),
                                            "GENERIC_DECLINE",
                                            veto.seq()))));
            verify(agents, timeout(2_000)).resolveVeto("agent", "call", "GENERIC_DECLINE", 1);
            if (!outcome.equals("accepted")) assertInstanceOf(Frame.Error.class, receive(terminal));
            assertTrue(terminal.send(FrameCodec.encode(new Frame.Heartbeat())));
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

    private static @NonNull Frame receive(ZMQ.@NonNull Socket dealer) {
        Frame frame;
        do {
            frame = receiveRaw(dealer);
        } while (frame instanceof Frame.RequestAccepted);
        return frame;
    }

    private static @NonNull Frame receiveRaw(ZMQ.@NonNull Socket dealer) {
        byte[] payload = dealer.recv();
        if (payload == null) throw new AssertionError("server did not respond");
        var frame = FrameCodec.decode(payload);
        if (frame == null) throw new AssertionError("server sent an invalid frame");
        return frame;
    }
}
