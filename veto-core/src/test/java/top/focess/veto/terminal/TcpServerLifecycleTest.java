package top.focess.veto.terminal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.channels.ServerSocketChannel;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import top.focess.veto.api.plugin.Scope;
import top.focess.veto.command.CommandRegistry;
import top.focess.veto.command.SessionCommandService;
import top.focess.veto.contract.Frame;
import top.focess.veto.contract.ProtocolClient;
import top.focess.veto.contract.Version;
import top.focess.veto.session.AgentSessions;
import top.focess.veto.session.SessionService;
import top.focess.veto.session.TestAgentSessions;
import top.focess.veto.transport.tcp.TcpConnection;

@Timeout(20)
class TcpServerLifecycleTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shutdownWaitsForRequestAndControlWorkAfterTheSocketCloses(boolean control) {
        runner("tcp://127.0.0.1:0")
                .run(
                        context -> {
                            var server = context.getBean(TcpServer.class);
                            var listenerField = TcpServer.class.getDeclaredField("listener");
                            listenerField.setAccessible(true);
                            var listener =
                                    assertInstanceOf(
                                            ServerSocketChannel.class, listenerField.get(server));
                            var entered = new CountDownLatch(1);
                            var release = new CountDownLatch(1);
                            var worker = new AtomicReference<Thread>();
                            var registry = context.getBean(CommandRegistry.class);
                            var answer =
                                    doAnswer(
                                            invocation -> {
                                                worker.set(Thread.currentThread());
                                                entered.countDown();
                                                try {
                                                    assertTrue(release.await(5, TimeUnit.SECONDS));
                                                } catch (InterruptedException cancelled) {
                                                    // A command cooperates with disconnect, but
                                                    // cleanup still must finish.
                                                    assertTrue(release.await(5, TimeUnit.SECONDS));
                                                }
                                                return control
                                                        ? new Frame.HintInfo("finished", null)
                                                        : new Frame.Done(
                                                                new Scope.GlobalScope(),
                                                                null,
                                                                -1,
                                                                null,
                                                                null,
                                                                2);
                                            });
                            if (control) answer.when(registry).hint(any(), eq("held"));
                            else answer.when(registry).dispatch(any(), eq("held"));
                            try (var shutdownWorkers = Executors.newVirtualThreadPerTaskExecutor();
                                    var client =
                                            TcpConnection.connect(
                                                    "tcp://127.0.0.1:"
                                                            + listener.socket().getLocalPort())) {
                                client.send(
                                        new Frame.Hello(
                                                Frame.PROTOCOL_VERSION, 1, Version.UNKNOWN, "."));
                                assertInstanceOf(Frame.Welcome.class, client.receive());
                                client.send(
                                        control
                                                ? new Frame.Hint("held", 2)
                                                : new Frame.Request(2, null, null, "held"));
                                assertTrue(entered.await(2, TimeUnit.SECONDS));
                                client.send(new Frame.Bye());
                                assertThrows(IOException.class, client::receive);
                                var shutdown =
                                        shutdownWorkers.submit(
                                                () -> {
                                                    server.stop();
                                                    return null;
                                                });
                                try {
                                    assertThrows(
                                            TimeoutException.class,
                                            () -> shutdown.get(100, TimeUnit.MILLISECONDS));
                                } finally {
                                    release.countDown();
                                }
                                shutdown.get(2, TimeUnit.SECONDS);
                                var running = worker.get();
                                assertNotNull(running);
                                assertFalse(
                                        running.isAlive(),
                                        "Server shutdown must join work from retired connections");
                            } finally {
                                release.countDown();
                            }
                        });
    }

    @Test
    void idleHeartbeatsAndBusinessRepliesWorkWithoutAckMatchingOnTheRealTcpConnection() {
        runner("tcp://127.0.0.1:0")
                .run(
                        context -> {
                            assertNull(context.getStartupFailure());
                            var server = context.getBean(TcpServer.class);
                            var listenerField = TcpServer.class.getDeclaredField("listener");
                            listenerField.setAccessible(true);
                            var listener =
                                    assertInstanceOf(
                                            ServerSocketChannel.class, listenerField.get(server));
                            var transport =
                                    spy(
                                            TcpConnection.connect(
                                                    "tcp://127.0.0.1:"
                                                            + listener.socket().getLocalPort()));
                            var acknowledgements = new CountDownLatch(2);
                            var discardedAcknowledgement = new CountDownLatch(1);
                            var discardAcknowledgements = new AtomicBoolean();
                            doAnswer(
                                            invocation -> {
                                                while (true) {
                                                    var frame = invocation.callRealMethod();
                                                    if (frame instanceof Frame.HeartbeatAck) {
                                                        if (discardAcknowledgements.get()) {
                                                            discardedAcknowledgement.countDown();
                                                            continue;
                                                        }
                                                        acknowledgements.countDown();
                                                    }
                                                    return frame;
                                                }
                                            })
                                    .when(transport)
                                    .receive();
                            var expected = new Frame.HintInfo("argument", null);
                            when(context.getBean(CommandRegistry.class).hint(any(), eq("command")))
                                    .thenReturn(expected);
                            try (var client =
                                    new ProtocolClient(transport, Version.UNKNOWN, ".", 100)) {
                                assertInstanceOf(
                                        Frame.Welcome.class, client.receive(2, TimeUnit.SECONDS));
                                assertTrue(acknowledgements.await(3, TimeUnit.SECONDS));
                                assertEquals(
                                        expected,
                                        assertInstanceOf(
                                                        Frame.HintResult.class,
                                                        client.hint("command", 2, TimeUnit.SECONDS))
                                                .hint());
                                discardAcknowledgements.set(true);
                                assertTrue(discardedAcknowledgement.await(3, TimeUnit.SECONDS));
                                clearInvocations(transport);
                                assertEquals(
                                        expected,
                                        assertInstanceOf(
                                                        Frame.HintResult.class,
                                                        client.hint("command", 2, TimeUnit.SECONDS))
                                                .hint());
                                assertFalse(client.isClosed());
                                assertNull(client.receive(11, TimeUnit.SECONDS));
                                assertFalse(
                                        client.isClosed(),
                                        "Missing Ack must not end an active TCP connection");
                                verify(transport, atLeast(2)).send(any(Frame.Heartbeat.class));
                                assertEquals(
                                        expected,
                                        assertInstanceOf(
                                                        Frame.HintResult.class,
                                                        client.hint("command", 2, TimeUnit.SECONDS))
                                                .hint());
                            }
                            verify(transport).close();
                        });
    }

    @Test
    void requestAndReplyLargerThanEightMiBPassTheActualTcpEndpoints() {
        runner("tcp://127.0.0.1:0")
                .run(
                        context -> {
                            assertNull(context.getStartupFailure());
                            var server = context.getBean(TcpServer.class);
                            var listenerField = TcpServer.class.getDeclaredField("listener");
                            listenerField.setAccessible(true);
                            var listener =
                                    assertInstanceOf(
                                            ServerSocketChannel.class, listenerField.get(server));
                            var text = "x".repeat(8 * 1024 * 1024) + "中文 🚀";
                            var expected = new Frame.HintInfo(text, null);
                            var registry = context.getBean(CommandRegistry.class);
                            when(registry.hint(any(), eq(text))).thenReturn(expected);
                            try (var client =
                                    new ProtocolClient(
                                            TcpConnection.connect(
                                                    "tcp://127.0.0.1:"
                                                            + listener.socket().getLocalPort()))) {
                                assertInstanceOf(
                                        Frame.Welcome.class, client.receive(2, TimeUnit.SECONDS));
                                var reply =
                                        assertInstanceOf(
                                                Frame.HintResult.class,
                                                client.hint(text, 5, TimeUnit.SECONDS));
                                assertTrue(
                                        expected.equals(reply.hint()),
                                        "TCP must preserve the complete large reply");
                                verify(registry).hint(any(), eq(text));
                                assertFalse(client.isClosed());
                            }
                        });
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = "false")
    void missingOrDisabledTerminalNeedsNeitherAnAddressNorItsDependencies(String enabled) {
        var runner =
                new ApplicationContextRunner()
                        .withUserConfiguration(TcpServer.class)
                        .withBean(EndpointConsumer.class);
        if (enabled != null) runner = runner.withPropertyValues("veto.terminal.enabled=" + enabled);
        runner.run(
                context -> {
                    assertNull(context.getStartupFailure());
                    assertTrue(context.getBeansOfType(TcpServer.class).isEmpty());
                    assertNull(context.getBean(EndpointConsumer.class).servers().getIfAvailable());
                });
    }

    @Test
    void occupiedEndpointFailsApplicationStartup() throws Exception {
        try (var occupied = new ServerSocket(0)) {
            runner("tcp://127.0.0.1:" + occupied.getLocalPort())
                    .run(
                            context ->
                                    assertInstanceOf(
                                            BeanCreationException.class,
                                            context.getStartupFailure()));
        }
    }

    @Test
    void repeatedColdHandshakesAndSpringShutdownCloseAllWorkers() {
        var acceptor = new AtomicReference<Thread>();
        var listener = new AtomicReference<ServerSocketChannel>();
        runner("tcp://127.0.0.1:0")
                .run(
                        context -> {
                            assertNull(context.getStartupFailure());
                            assertEquals(1, context.getBeansOfType(TcpServer.class).size());
                            var server = context.getBean(TcpServer.class);
                            var listenerField = TcpServer.class.getDeclaredField("listener");
                            listenerField.setAccessible(true);
                            var socket =
                                    assertInstanceOf(
                                            ServerSocketChannel.class, listenerField.get(server));
                            assertTrue(socket.isOpen());
                            listener.set(socket);
                            var worker = worker(server, "acceptor");
                            assertTrue(worker.isVirtual());
                            assertTrue(worker.isAlive());
                            acceptor.set(worker);
                            var address = "tcp://127.0.0.1:" + socket.socket().getLocalPort();
                            for (int attempt = 0; attempt < 100; attempt++) {
                                try (var client = TcpConnection.connect(address)) {
                                    client.send(
                                            new Frame.Hello(
                                                    Frame.PROTOCOL_VERSION,
                                                    1,
                                                    Version.UNKNOWN,
                                                    "."));
                                    assertInstanceOf(Frame.Welcome.class, client.receive());
                                }
                            }
                            try (var client = TcpConnection.connect(address)) {
                                client.send(
                                        new Frame.Hello(
                                                Frame.PROTOCOL_VERSION, 1, Version.UNKNOWN, "."));
                                assertInstanceOf(Frame.Welcome.class, client.receive());
                                var connectionsField =
                                        TcpServer.class.getDeclaredField("connections");
                                connectionsField.setAccessible(true);
                                if (!(connectionsField.get(server)
                                        instanceof Map<?, ?> connections))
                                    throw new AssertionError("Missing connections");
                                var workersField = TcpServer.class.getDeclaredField("workers");
                                workersField.setAccessible(true);
                                var workers =
                                        assertInstanceOf(
                                                ExecutorService.class, workersField.get(server));
                                server.stop();
                                assertThrows(IOException.class, client::receive);
                                assertTrue(workers.isTerminated());
                                assertTrue(connections.isEmpty());
                            }
                        });
        var worker = acceptor.get();
        var socket = listener.get();
        if (worker == null || socket == null) throw new AssertionError("Missing TCP resources");
        assertFalse(worker.isAlive());
        assertFalse(socket.isOpen());
    }

    @Test
    void dependencyFailureAbortsStartupBeforeBindingTheListener() throws Exception {
        var address = freeAddress();
        var unavailable = new IllegalStateException("command registry unavailable");
        new ApplicationContextRunner()
                .withUserConfiguration(TcpServer.class)
                .withPropertyValues(
                        "veto.terminal.enabled=true", "veto.terminal.bind-address=" + address)
                .withBean(AgentSessions.class, TestAgentSessions::create)
                .withBean(SessionService.class, () -> mock(SessionService.class))
                .withBean(SessionCommandService.class, () -> mock(SessionCommandService.class))
                .withBean(
                        CommandRegistry.class,
                        () -> {
                            throw unavailable;
                        })
                .run(
                        context -> {
                            var failure =
                                    assertInstanceOf(
                                            BeanCreationException.class,
                                            context.getStartupFailure());
                            assertSame(unavailable, failure.getMostSpecificCause());
                        });
        try (var replacement = TcpConnection.bind(address)) {
            assertTrue(replacement.socket().isBound());
        }
    }

    private static @NonNull Thread worker(@NonNull Object owner, @NonNull String field) {
        try {
            var declared = owner.getClass().getDeclaredField(field);
            declared.setAccessible(true);
            return assertInstanceOf(Thread.class, declared.get(owner));
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static @NonNull ApplicationContextRunner runner(@NonNull String address) {
        return new ApplicationContextRunner()
                .withUserConfiguration(TcpServer.class)
                .withPropertyValues(
                        "veto.terminal.enabled=true", "veto.terminal.bind-address=" + address)
                .withBean(CommandRegistry.class, () -> mock(CommandRegistry.class))
                .withBean(AgentSessions.class, TestAgentSessions::create)
                .withBean(SessionService.class, () -> mock(SessionService.class))
                .withBean(SessionCommandService.class, () -> mock(SessionCommandService.class))
                .withBean(EndpointConsumer.class);
    }

    private static @NonNull String freeAddress() throws IOException {
        try (var reservation = new ServerSocket(0)) {
            return "tcp://127.0.0.1:" + reservation.getLocalPort();
        }
    }

    record EndpointConsumer(@NonNull ObjectProvider<TcpServer> servers) {}
}
