package top.focess.veto.terminal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.channels.ServerSocketChannel;
import java.util.Map;
import java.util.concurrent.ExecutorService;
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
import top.focess.veto.command.CommandRegistry;
import top.focess.veto.command.SessionCommandService;
import top.focess.veto.contract.Frame;
import top.focess.veto.contract.Version;
import top.focess.veto.session.AgentSessions;
import top.focess.veto.session.SessionService;
import top.focess.veto.session.TestAgentSessions;
import top.focess.veto.transport.tcp.TcpConnection;

@Timeout(20)
class IpcServerLifecycleTest {
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = "false")
    void missingOrDisabledTerminalNeedsNeitherAnAddressNorItsDependencies(String enabled) {
        var runner =
                new ApplicationContextRunner()
                        .withUserConfiguration(IpcServer.class)
                        .withBean(EndpointConsumer.class);
        if (enabled != null) runner = runner.withPropertyValues("veto.terminal.enabled=" + enabled);
        runner.run(
                context -> {
                    assertNull(context.getStartupFailure());
                    assertTrue(context.getBeansOfType(IpcServer.class).isEmpty());
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
                            assertEquals(1, context.getBeansOfType(IpcServer.class).size());
                            var server = context.getBean(IpcServer.class);
                            var listenerField = IpcServer.class.getDeclaredField("listener");
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
                                        IpcServer.class.getDeclaredField("connections");
                                connectionsField.setAccessible(true);
                                if (!(connectionsField.get(server)
                                        instanceof Map<?, ?> connections))
                                    throw new AssertionError("Missing connections");
                                var workersField = IpcServer.class.getDeclaredField("workers");
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
        if (worker == null || socket == null) throw new AssertionError("Missing IPC resources");
        assertFalse(worker.isAlive());
        assertFalse(socket.isOpen());
    }

    @Test
    void dependencyFailureAbortsStartupBeforeBindingTheListener() throws Exception {
        var address = freeAddress();
        var unavailable = new IllegalStateException("command registry unavailable");
        new ApplicationContextRunner()
                .withUserConfiguration(IpcServer.class)
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
                .withUserConfiguration(IpcServer.class)
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

    record EndpointConsumer(@NonNull ObjectProvider<IpcServer> servers) {}
}
