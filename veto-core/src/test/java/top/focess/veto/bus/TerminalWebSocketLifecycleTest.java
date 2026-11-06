package top.focess.veto.bus;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.handler.SimpleUrlHandlerMapping;
import top.focess.veto.agent.AgentService;
import top.focess.veto.api.plugin.Scope;
import top.focess.veto.command.CommandRegistry;
import top.focess.veto.command.VetoCommandSender;
import top.focess.veto.contract.Frame;
import top.focess.veto.contract.Version;
import top.focess.veto.session.TestAgentSessions;
import top.focess.veto.vault.LoginSessionManager;
import top.focess.veto.vault.TestUsers;

@Timeout(20)
class TerminalWebSocketLifecycleTest {
    @Test
    void restartedTerminalRestoresTokenIdentityAndRevocationStopsFurtherWork() throws Exception {
        var registry = mock(CommandRegistry.class);
        doCallRealMethod().when(registry).terminalOutcome(any(), any());
        when(registry.dispatch(any(), eq("identity")))
                .thenAnswer(
                        invocation -> {
                            VetoCommandSender sender = invocation.getArgument(0);
                            if (sender == null) throw new AssertionError("Missing command sender");
                            assertEquals(TestUsers.ALICE, sender.userId());
                            assertEquals("alice", sender.username());
                            return new Frame.Done(
                                    new Scope.GlobalScope(), null, -1, "restored", null, 0);
                        });
        try (var context =
                TerminalWebSocketTestServer.open(
                        registry, mock(AgentService.class), TestAgentSessions.create(), 0)) {
            var token = TerminalWebSocketTestServer.token(context);
            var address = TerminalWebSocketTestServer.address(context);
            for (int attempt = 0; attempt < 2; attempt++) {
                try (var client = WebSocketTestClient.connect(address, token)) {
                    client.send(new Frame.Hello(Frame.PROTOCOL_VERSION, 1, Version.UNKNOWN, "."));
                    assertInstanceOf(Frame.Welcome.class, client.receive());
                    client.send(new Frame.Request(2, null, null, "identity"));
                    assertEquals(
                            2,
                            assertInstanceOf(Frame.RequestAccepted.class, client.receive()).seq());
                    assertEquals(
                            "restored",
                            assertInstanceOf(Frame.Done.class, client.receive()).content());
                    if (attempt == 1) {
                        context.getBean(LoginSessionManager.class).revokeToken(token);
                        client.send(new Frame.Request(3, null, null, "identity"));
                        assertThrows(IOException.class, client::receive);
                    }
                }
            }
            verify(registry, times(2)).dispatch(any(), eq("identity"));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shutdownWaitsForRequestAndControlWorkAfterTheSocketCloses(boolean control)
            throws Exception {
        var registry = mock(CommandRegistry.class);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var worker = new AtomicReference<Thread>();
        var answer =
                doAnswer(
                        invocation -> {
                            worker.set(Thread.currentThread());
                            entered.countDown();
                            try {
                                assertTrue(release.await(5, TimeUnit.SECONDS));
                            } catch (InterruptedException cancelled) {
                                // Disconnect signals the command, but its cleanup still has to
                                // finish.
                                assertTrue(release.await(5, TimeUnit.SECONDS));
                            }
                            return control
                                    ? new Frame.HintInfo("finished", null)
                                    : new Frame.Done(
                                            new Scope.GlobalScope(), null, -1, null, null, 2);
                        });
        if (control) answer.when(registry).hint(any(), eq("held"));
        else answer.when(registry).dispatch(any(), eq("held"));
        try (var context =
                        TerminalWebSocketTestServer.open(
                                registry, mock(AgentService.class), TestAgentSessions.create(), 0);
                var shutdownWorkers = Executors.newVirtualThreadPerTaskExecutor();
                var client =
                        WebSocketTestClient.connect(
                                TerminalWebSocketTestServer.address(context),
                                TerminalWebSocketTestServer.token(context))) {
            client.send(new Frame.Hello(Frame.PROTOCOL_VERSION, 1, Version.UNKNOWN, "."));
            assertInstanceOf(Frame.Welcome.class, client.receive());
            client.send(
                    control ? new Frame.Hint("held", 2) : new Frame.Request(2, null, null, "held"));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            client.send(new Frame.Bye());
            assertThrows(IOException.class, client::receive);
            var shutdown = shutdownWorkers.submit(context.getBean(WebSocketServer.class)::stop);
            try {
                assertThrows(
                        TimeoutException.class, () -> shutdown.get(100, TimeUnit.MILLISECONDS));
            } finally {
                release.countDown();
            }
            shutdown.get(2, TimeUnit.SECONDS);
            var running = worker.get();
            assertNotNull(running);
            assertFalse(running.isAlive(), "Shutdown must join work from retired connections");
        } finally {
            release.countDown();
        }
    }

    @Test
    void heartbeatAndBusinessFramesShareTheActualWebSocketConnection() throws Exception {
        var registry = mock(CommandRegistry.class);
        var expected = new Frame.HintInfo("argument", null);
        when(registry.hint(any(), eq("command"))).thenReturn(expected);
        try (var context =
                        TerminalWebSocketTestServer.open(
                                registry, mock(AgentService.class), TestAgentSessions.create(), 0);
                var client =
                        WebSocketTestClient.connect(
                                TerminalWebSocketTestServer.address(context),
                                TerminalWebSocketTestServer.token(context))) {
            client.send(new Frame.Hello(Frame.PROTOCOL_VERSION, 1, Version.UNKNOWN, "."));
            assertInstanceOf(Frame.Welcome.class, client.receive());
            for (int seq = 2; seq < 5; seq++) {
                client.send(new Frame.Heartbeat());
                assertInstanceOf(Frame.HeartbeatAck.class, client.receive());
                client.send(new Frame.Hint("command", seq));
                var result = assertInstanceOf(Frame.HintResult.class, client.receive());
                assertEquals(seq, result.seq());
                assertEquals(expected, result.hint());
            }
        }
    }

    @Test
    void requestAndReplyLargerThanEightMiBPassTheActualWebSocketEndpoints() throws Exception {
        var text = "x".repeat(8 * 1024 * 1024) + "中文 🚀";
        var expected = new Frame.HintInfo(text, null);
        var registry = mock(CommandRegistry.class);
        when(registry.hint(any(), eq(text))).thenReturn(expected);
        try (var context =
                        TerminalWebSocketTestServer.open(
                                registry, mock(AgentService.class), TestAgentSessions.create(), 0);
                var client =
                        WebSocketTestClient.connect(
                                TerminalWebSocketTestServer.address(context),
                                TerminalWebSocketTestServer.token(context))) {
            client.send(new Frame.Hello(Frame.PROTOCOL_VERSION, 1, Version.UNKNOWN, "."));
            assertInstanceOf(Frame.Welcome.class, client.receive());
            client.send(new Frame.Hint(text, 2));
            var reply = assertInstanceOf(Frame.HintResult.class, client.receive());
            assertEquals(2, reply.seq());
            assertTrue(
                    expected.equals(reply.hint()),
                    "WebSocket must preserve the complete large reply");
            verify(registry).hint(any(), eq(text));
        }
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"false", "true"})
    void terminalPathRequiresExplicitEnablementWhileBrowserRemainsRegistered(String enabled) {
        var runner =
                new WebApplicationContextRunner()
                        .withUserConfiguration(WebSocketConfig.class)
                        .withBean(WebSocketServer.class, () -> mock(WebSocketServer.class))
                        .withBean(BusConfiguration.class, BusConfiguration::new)
                        .withBean(
                                VetoWebSocketAuthInterceptor.class,
                                () -> new VetoWebSocketAuthInterceptor(new LoginSessionManager()));
        if (enabled != null) runner = runner.withPropertyValues("veto.terminal.enabled=" + enabled);
        runner.run(
                context -> {
                    assertNull(context.getStartupFailure());
                    var routes =
                            context.getBean(
                                            "webSocketHandlerMapping",
                                            SimpleUrlHandlerMapping.class)
                                    .getUrlMap();
                    assertTrue(routes.containsKey("/ws/veto/bus"));
                    assertEquals("true".equals(enabled), routes.containsKey("/ws/veto/terminal"));
                });
    }

    @Test
    void terminalAndBrowserShareTheConfiguredOriginPolicy() throws Exception {
        try (var context =
                        TerminalWebSocketTestServer.open(
                                mock(CommandRegistry.class),
                                mock(AgentService.class),
                                TestAgentSessions.create(),
                                0);
                var client = HttpClient.newHttpClient()) {
            var terminal = TerminalWebSocketTestServer.address(context);
            var token =
                    context.getBean(LoginSessionManager.class)
                            .createLoginSession(TestUsers.ALICE, "alice");
            for (String address :
                    List.of(terminal, terminal.replace("/terminal", "/bus?token=" + token))) {
                var failure =
                        assertThrows(
                                ExecutionException.class,
                                () ->
                                        client.newWebSocketBuilder()
                                                .header("X-Veto-Session-Token", token)
                                                .header("Origin", "https://untrusted.example")
                                                .buildAsync(
                                                        URI.create(address),
                                                        new WebSocket.Listener() {})
                                                .get(10, TimeUnit.SECONDS));
                var rejected =
                        assertInstanceOf(WebSocketHandshakeException.class, failure.getCause());
                assertEquals(403, rejected.getResponse().statusCode());
            }
        }
    }

    @Test
    void repeatedHandshakesAndSpringShutdownCloseAllWorkers() throws Exception {
        try (var context =
                TerminalWebSocketTestServer.open(
                        mock(CommandRegistry.class),
                        mock(AgentService.class),
                        TestAgentSessions.create(),
                        0)) {
            var server = context.getBean(WebSocketServer.class);
            var address = TerminalWebSocketTestServer.address(context);
            for (int attempt = 0; attempt < 3; attempt++) {
                try (var client =
                        WebSocketTestClient.connect(
                                address, TerminalWebSocketTestServer.token(context))) {
                    client.send(new Frame.Hello(Frame.PROTOCOL_VERSION, 1, Version.UNKNOWN, "."));
                    assertInstanceOf(Frame.Welcome.class, client.receive());
                }
            }
            try (var client =
                    WebSocketTestClient.connect(
                            address, TerminalWebSocketTestServer.token(context))) {
                client.send(new Frame.Hello(Frame.PROTOCOL_VERSION, 1, Version.UNKNOWN, "."));
                assertInstanceOf(Frame.Welcome.class, client.receive());
                var connections =
                        assertInstanceOf(
                                Map.class, ReflectionTestUtils.getField(server, "connections"));
                var workers =
                        assertInstanceOf(
                                ExecutorService.class,
                                ReflectionTestUtils.getField(server, "workers"));
                server.stop();
                assertThrows(IOException.class, client::receive);
                assertTrue(workers.isTerminated());
                assertTrue(connections.isEmpty());
            }
        }
    }
}
