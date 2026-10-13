package top.focess.veto.terminal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
import top.focess.veto.bus.DeltaBroker;
import top.focess.veto.command.CommandRegistry;
import top.focess.veto.command.PromptHandler;
import top.focess.veto.command.VetoCommandSender;
import top.focess.veto.contract.Delta;
import top.focess.veto.contract.Frame;
import top.focess.veto.contract.FrameCodec;
import top.focess.veto.contract.Version;
import top.focess.veto.event.EventManager;
import top.focess.veto.vault.AuthLifecycleManager;
import top.focess.veto.vault.CurrentUser;
import top.focess.veto.vault.KeysteadVault;
import top.focess.veto.vault.LoginSessionManager;
import top.focess.veto.vault.TestUsers;

class IpcPeerIsolationTest {
    private static @NonNull AnnotationConfigApplicationContext openEndpoint(
            @NonNull CommandRegistry registry,
            @NonNull AgentService agents,
            @NonNull DeltaBroker broker,
            @NonNull String address) {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment()
                .getPropertySources()
                .addFirst(
                        new MapPropertySource(
                                "terminal-test", Map.of("veto.terminal.bind-address", address)));
        context.registerBean(CommandRegistry.class, () -> registry);
        context.registerBean(AgentService.class, () -> agents);
        context.registerBean(DeltaBroker.class, () -> broker);
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
                                return new Frame.Done("fresh login");
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
                                return new Frame.Done("logout complete");
                            }
                            sender.output("fresh command output");
                            return new Frame.Done("fresh command complete");
                        });
        var address = "tcp://127.0.0.1:" + port;
        var endpoint = openEndpoint(registry, mock(AgentService.class), new DeltaBroker(), address);
        var server = endpoint.getBean(IpcServer.class);
        try (var context = new ZContext()) {
            var terminal = connect(context, address, "revoked-command");
            assertEquals("fresh login", request(terminal, "login").content());
            assertTrue(terminal.send(FrameCodec.encode(new Frame.Request("private"))));
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
            assertTrue(terminal.send(FrameCodec.encode(new Frame.Request("fresh"))));
            var output = assertInstanceOf(Delta.class, receive(terminal));
            assertEquals(
                    "fresh command output",
                    assertInstanceOf(Delta.Message.class, output.data()).text());
            var fresh = assertInstanceOf(Frame.Done.class, receive(terminal));

            assertEquals("fresh command complete", fresh.content());
            assertTrue(terminal.send(FrameCodec.encode(new Frame.Request("logout"))));
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
                            return new Frame.Done("login");
                        });
        when(registry.complete(any(), anyString()))
                .thenAnswer(
                        invocation -> {
                            entered.countDown();
                            assertTrue(release.await(5, TimeUnit.SECONDS));
                            return List.of(new Frame.Completion("private-credential", null, null));
                        });
        var address = "tcp://127.0.0.1:" + port;
        var endpoint = openEndpoint(registry, mock(AgentService.class), new DeltaBroker(), address);
        var server = endpoint.getBean(IpcServer.class);
        try (var context = new ZContext()) {
            var terminal = connect(context, address, "revoked-completion");
            request(terminal, "login");
            assertTrue(terminal.send(FrameCodec.encode(new Frame.Complete("/credential", 7))));
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
                            return new Frame.Done("logged in");
                        });
        var address = "tcp://127.0.0.1:" + port;
        var endpoint = openEndpoint(registry, mock(AgentService.class), new DeltaBroker(), address);
        var server = endpoint.getBean(IpcServer.class);
        try (var context = new ZContext()) {
            var terminal = connect(context, address, "auth-incarnation");
            request(terminal, "login");
            var sender = senderRef.get();
            if (sender == null) throw new AssertionError("Missing sender");
            synchronized (sender) {
                sender.output("private queued text");
                server.send(sender, new Frame.Done("private result"));
                server.revokeUser(TestUsers.ALICE);
            }
            var cancelled = assertInstanceOf(Frame.Done.class, receive(terminal));

            assertNull(cancelled.content());
            assertNull(cancelled.username());
            assertInstanceOf(Scope.GlobalScope.class, cancelled.scope());
            server.send(
                    sender,
                    new Frame.Error(
                            new Scope.SessionScope(TestUsers.ALICE, UUID.randomUUID().toString()),
                            null,
                            -1,
                            "private result arriving after logout",
                            0,
                            null));
            var late = assertInstanceOf(Frame.Done.class, receive(terminal));

            assertNull(late.content());
            assertInstanceOf(Scope.GlobalScope.class, late.scope());

            request(terminal, "login-again");
            synchronized (sender) {
                sender.output("old login incarnation");
                server.send(sender, new Frame.Done("old login result"));
                server.revokeUser(TestUsers.ALICE);
                sender.setUser(alice);
            }
            var refreshed = assertInstanceOf(Frame.Done.class, receive(terminal));

            assertNull(refreshed.content());
            assertEquals(TestUsers.ALICE, refreshed.scope().userId());
            assertTrue(terminal.send(FrameCodec.encode(new Frame.Heartbeat())));
            assertInstanceOf(Frame.HeartbeatAck.class, receive(terminal));
            sender.beginExecution();
            try {
                var revocation = Thread.ofVirtual().start(() -> server.revokeUser(TestUsers.ALICE));
                revocation.join(3_000);
                assertFalse(revocation.isAlive());
                server.send(sender, new Frame.HeartbeatAck(Instant.now()));
                assertInstanceOf(Frame.HeartbeatAck.class, receive(terminal));
            } finally {
                sender.endExecution();
            }
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
                            return new Frame.Done("logged in");
                        });
        var address = "tcp://127.0.0.1:" + port;
        var endpoint = openEndpoint(registry, mock(AgentService.class), new DeltaBroker(), address);
        var server = endpoint.getBean(IpcServer.class);
        try (var context = new ZContext()) {
            var terminal = connect(context, address, "reused-routing-identity");
            request(terminal, "login");
            var sender = senderRef.get();
            if (sender == null) throw new AssertionError("Missing sender");
            assertTrue(terminal.send(FrameCodec.encode(new Frame.Bye())));
            var sessionsField = IpcServer.class.getDeclaredField("sessions");
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
            sender.sendEvent(delta);
            sender.output("retired connection text");
            assertTrue(terminal.send(FrameCodec.encode(new Frame.Heartbeat())));
            assertInstanceOf(Frame.Terminate.class, receive(terminal));
            assertTrue(
                    terminal.send(
                            FrameCodec.encode(
                                    new Frame.Hello(
                                            Frame.PROTOCOL_VERSION, 2, Version.UNKNOWN, "."))));
            assertInstanceOf(Frame.Welcome.class, receive(terminal));
            sender.sendEvent(delta);
            sender.output("retired connection text");
            server.send(sender, new Frame.Done("retired completion"));
            assertTrue(terminal.send(FrameCodec.encode(new Frame.Heartbeat())));
            assertInstanceOf(Frame.HeartbeatAck.class, receive(terminal));
        } finally {
            endpoint.close();
        }
    }

    @Test
    @Timeout(20)
    @SuppressWarnings("try") // The test owns its broker subscription for the simulated episode.
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
                            return new Frame.Done("logged in");
                        });
        var broker = new DeltaBroker();
        var address = "tcp://127.0.0.1:" + port;
        var endpoint = openEndpoint(registry, agents, broker, address);
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
            sender.sendVetoPrompt(TestUsers.ALICE, sessionId, prompt);
            var veto = assertInstanceOf(Frame.Veto.class, receive(terminal));
            var delta =
                    new Delta(
                            new Scope.AgentScope(TestUsers.ALICE, sessionId.toString(), "agent"),
                            new Delta.Thought("private", 1));
            try (var subscription = broker.subscribe(sessionId, sender::sendEvent)) {
                synchronized (sender) {
                    broker.publish(
                            delta); // Already queued, but IO cannot enter the sender boundary.
                    server.revokeUser(TestUsers.ALICE);
                }
                broker.publish(delta);
                sender.sendVetoPrompt(TestUsers.ALICE, sessionId, prompt);
                var reply =
                        new Frame.VetoResult(
                                veto.scope(), veto.callId(), "ACCEPT_GENERIC", veto.seq());
                assertTrue(terminal.send(FrameCodec.encode(reply)));
                assertTrue(terminal.send(FrameCodec.encode(new Frame.Heartbeat())));
                assertInstanceOf(Frame.HeartbeatAck.class, receive(terminal));
                verify(agents, never())
                        .resolveVeto(anyString(), anyString(), anyString(), anyLong());
                request(terminal, "login-again");
                assertTrue(terminal.send(FrameCodec.encode(reply)));
                assertTrue(terminal.send(FrameCodec.encode(new Frame.Heartbeat())));
                assertInstanceOf(Frame.HeartbeatAck.class, receive(terminal));
                verify(agents, never())
                        .resolveVeto(anyString(), anyString(), anyString(), anyLong());
                sender.sendVetoPrompt(
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
                verify(agents).resolveVeto("agent", "call", "ACCEPT_GENERIC", 2);
                broker.publish(delta);
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
        when(registry.activeSession("plugin-peer")).thenReturn(selected);
        when(registry.dispatch(any(), anyString()))
                .thenAnswer(
                        invocation -> {
                            VetoCommandSender sender = invocation.getArgument(0);
                            if (sender == null) throw new AssertionError("Missing sender");
                            sender.setUser(alice);
                            return new Frame.Done("logged in");
                        });
        var broker = new DeltaBroker();
        var address = "tcp://127.0.0.1:" + port;
        var endpoint = openEndpoint(registry, mock(AgentService.class), broker, address);
        var server = endpoint.getBean(IpcServer.class);
        try (var context = new ZContext()) {
            var terminal = connect(context, address, "plugin-peer");
            request(terminal, "login");
            var data = JsonNodeFactory.instance.objectNode().put("status", "ready");
            broker.publish(
                    new Frame.PluginAction(
                            new Scope.SessionScope(TestUsers.BOB, selected), "other-owner", data));
            broker.publish(
                    new Frame.PluginAction(
                            new Scope.SessionScope(TestUsers.ALICE, UUID.randomUUID().toString()),
                            "other-session",
                            data));
            var expected =
                    new Frame.PluginAction(
                            new Scope.SessionScope(TestUsers.ALICE, selected), "task", data);
            broker.publish(expected);
            assertEquals(expected, receive(terminal));
            broker.publish(
                    new Frame.Action(
                            new Scope.SessionScope(TestUsers.BOB, selected), List.of("agents")));
            broker.publish(
                    new Frame.Action(
                            new Scope.SessionScope(TestUsers.ALICE, UUID.randomUUID().toString()),
                            List.of("agents")));
            var action =
                    new Frame.Action(
                            new Scope.SessionScope(TestUsers.ALICE, selected), List.of("agents"));
            broker.publish(action);
            assertEquals(action, receive(terminal));
            server.revokeUser(TestUsers.ALICE);
            broker.publish(expected);
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
                            return new Frame.Done(Boolean.toString(sender.isLoggedIn()));
                        });
        var endpoint = openEndpoint(registry, mock(AgentService.class), new DeltaBroker(), address);
        var server = endpoint.getBean(IpcServer.class);
        try (var context = new ZContext()) {
            var terminal = connect(context, address, "pending-input");
            assertEquals("true", request(terminal, "login").content());
            assertTrue(terminal.send("{\"type\":\"request\",\"raw\":\"prompt\"}"));
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
                            return new Frame.Done(Boolean.toString(sender.isLoggedIn()));
                        });
        when(registry.complete(any(), anyString())).thenReturn(List.of());
        var endpoint = openEndpoint(registry, mock(AgentService.class), new DeltaBroker(), address);
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
            assertTrue(first.send("{\"type\":\"request\",\"raw\":\"hold\"}"));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertTrue(first.send("{\"type\":\"request\",\"raw\":\"queued\"}"));
            // Completion is ordered behind the queued frame on the session mailbox.
            assertTrue(first.send("{\"type\":\"complete\",\"raw\":\"barrier\",\"seq\":2}"));
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
        assertTrue(dealer.send("{\"type\":\"request\",\"raw\":\"" + raw + "\"}"));
        return assertInstanceOf(Frame.Done.class, receive(dealer));
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
        var endpoint = openEndpoint(registry, mock(AgentService.class), new DeltaBroker(), address);
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
        var endpoint = openEndpoint(registry, mock(AgentService.class), new DeltaBroker(), address);
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
                    openEndpoint(registry, mock(AgentService.class), new DeltaBroker(), address);
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
                            return new Frame.Done(raw);
                        });
        when(registry.complete(any(), anyString())).thenReturn(List.of());
        var endpoint = openEndpoint(registry, mock(AgentService.class), new DeltaBroker(), address);
        try (var context = new ZContext()) {
            var terminal = connect(context, address, "cancellation-peer");
            assertTrue(terminal.send(FrameCodec.encode(new Frame.Request("hold"))));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertTrue(terminal.send(FrameCodec.encode(new Frame.Cancel(6))));
            assertTrue(interrupted.await(2, TimeUnit.SECONDS));
            assertEquals(new Frame.CancelResult(Frame.CancelStatus.ACCEPTED, 6), receive(terminal));
            assertTrue(terminal.send(FrameCodec.encode(new Frame.Request("next"))));
            assertTrue(terminal.send(FrameCodec.encode(new Frame.Complete("barrier", 8))));
            assertInstanceOf(Frame.CompleteResult.class, receive(terminal));
            assertEquals(
                    1,
                    nextEntered.getCount(),
                    "replacement body must wait for cancelled body exit");
            assertTrue(terminal.send(FrameCodec.encode(new Frame.Cancel(9))));
            assertEquals(new Frame.CancelResult(Frame.CancelStatus.ACCEPTED, 9), receive(terminal));
            assertTrue(terminal.send(FrameCodec.encode(new Frame.Heartbeat())));
            assertInstanceOf(Frame.HeartbeatAck.class, receive(terminal));
            release.countDown();
            var cancelled = assertInstanceOf(Frame.Done.class, receive(terminal));
            assertNull(cancelled.content());
            var completed = assertInstanceOf(Frame.Done.class, receive(terminal));
            assertEquals("next", completed.content());
            assertTrue(nextEntered.await(1, TimeUnit.SECONDS));
            assertTrue(terminal.send(FrameCodec.encode(new Frame.Cancel(10))));
            assertEquals(
                    new Frame.CancelResult(Frame.CancelStatus.NO_ACTIVE_REQUEST, 10),
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
                            return new Frame.Done("prompt dismissed");
                        });
        var address = "tcp://127.0.0.1:" + port;
        var endpoint = openEndpoint(registry, mock(AgentService.class), new DeltaBroker(), address);
        try (var context = new ZContext()) {
            var terminal = connect(context, address, "cancel-prompt");
            assertTrue(terminal.send(FrameCodec.encode(new Frame.Request("prompt"))));
            assertInstanceOf(Frame.Prompt.class, receive(terminal));
            assertTrue(terminal.send(FrameCodec.encode(new Frame.Cancel(2))));
            assertEquals(new Frame.CancelResult(Frame.CancelStatus.ACCEPTED, 2), receive(terminal));
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
    void approvalCancellationReportsItsResultEvenWithoutAnActiveCommand(@NonNull String outcome)
            throws Exception {
        int port;
        try (var reservation = new ServerSocket(0)) {
            port = reservation.getLocalPort();
        }
        var alice = TestUsers.registry().findByUserId(TestUsers.ALICE).orElseThrow();
        var registry = mock(CommandRegistry.class);
        var agents = mock(AgentService.class);
        if (outcome.equals("exception")) {
            when(agents.declineVeto("agent", "call"))
                    .thenThrow(new IllegalStateException("decline failed"));
        } else {
            when(agents.declineVeto("agent", "call")).thenReturn(outcome.equals("accepted"));
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
                            return new Frame.Done("logged in");
                        });
        var address = "tcp://127.0.0.1:" + port;
        var endpoint = openEndpoint(registry, agents, new DeltaBroker(), address);
        try (var context = new ZContext()) {
            var terminal = connect(context, address, "cancel-approval");
            request(terminal, "login");
            var sender = senderRef.get();
            if (sender == null) throw new AssertionError("Missing sender");
            sender.sendVetoPrompt(
                    TestUsers.ALICE,
                    UUID.randomUUID(),
                    new VetoPrompt(
                            "agent",
                            "call",
                            "tool",
                            VetoScenario.GENERIC,
                            List.of(VetoOption.ACCEPT_GENERIC),
                            Map.of(),
                            null,
                            null,
                            1));
            assertInstanceOf(Frame.Veto.class, receive(terminal));
            assertTrue(terminal.send(FrameCodec.encode(new Frame.Cancel(2))));
            var expected =
                    outcome.equals("accepted")
                            ? Frame.CancelStatus.ACCEPTED
                            : Frame.CancelStatus.REJECTED;
            assertEquals(new Frame.CancelResult(expected, 2), receive(terminal));
            verify(agents).declineVeto("agent", "call");
            assertTrue(terminal.send(FrameCodec.encode(new Frame.Cancel(3))));
            assertEquals(
                    new Frame.CancelResult(Frame.CancelStatus.NO_ACTIVE_REQUEST, 3),
                    receive(terminal));
            assertTrue(terminal.send(FrameCodec.encode(new Frame.Heartbeat())));
            assertInstanceOf(Frame.HeartbeatAck.class, receive(terminal));
        } finally {
            endpoint.close();
        }
    }

    private static @NonNull Frame receive(ZMQ.@NonNull Socket dealer) {
        byte[] payload = dealer.recv();
        if (payload == null) throw new AssertionError("server did not respond");
        var frame = FrameCodec.decode(payload);
        if (frame == null) throw new AssertionError("server sent an invalid frame");
        return frame;
    }
}
