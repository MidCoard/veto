package top.focess.veto.terminal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.net.ServerSocket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.zeromq.ZContext;
import top.focess.veto.command.CommandRegistry;
import top.focess.veto.command.SessionCommandService;
import top.focess.veto.contract.Frame;
import top.focess.veto.contract.ServerTransport;
import top.focess.veto.contract.Version;
import top.focess.veto.session.AgentSessions;
import top.focess.veto.session.SessionService;
import top.focess.veto.session.TestAgentSessions;
import top.focess.veto.transport.zmq.ZmqChannel;

@Timeout(20)
class TerminalConfigurationTest {
    @Test
    void disabledTerminalNeedsNeitherAnAddressNorItsDependencies() {
        new ApplicationContextRunner()
                .withUserConfiguration(TerminalConfiguration.class)
                .withBean(EndpointConsumer.class)
                .withPropertyValues("veto.terminal.enabled=false")
                .run(
                        context -> {
                            assertNull(context.getStartupFailure());
                            assertNull(
                                    context.getBean(EndpointConsumer.class)
                                            .servers()
                                            .getIfAvailable());
                        });
    }

    @Test
    void occupiedEndpointFailsApplicationStartup() throws Exception {
        try (var occupied = new ServerSocket(0)) {
            runner("tcp://127.0.0.1:" + occupied.getLocalPort())
                    .run(
                            context -> {
                                assertInstanceOf(
                                        BeanCreationException.class, context.getStartupFailure());
                            });
        }
    }

    @Test
    void springPublishesResourceBeansAndJoinsTheWorkerBeforeClosingTransport() throws Exception {
        var address = freeAddress();
        var ioWorker = new AtomicReference<Thread>();
        runner(address)
                .run(
                        context -> {
                            assertNull(context.getStartupFailure());
                            assertTrue(
                                    context.getBean(EndpointConsumer.class)
                                                    .servers()
                                                    .getIfAvailable()
                                            != null);
                            assertTrue(context.getBean("ipcContext") instanceof ZContext);
                            assertTrue(
                                    context.getBean("ipcTransport") instanceof ZmqChannel.Server);
                            assertFalse(context.containsBean("ipcSubscription"));
                            var workerField = IpcServer.class.getDeclaredField("ioThread");
                            workerField.setAccessible(true);
                            var worker = (Thread) workerField.get(context.getBean(IpcServer.class));
                            if (worker == null) throw new AssertionError("Missing IO worker");
                            assertTrue(worker.isVirtual());
                            assertTrue(worker.isAlive());
                            ioWorker.set(worker);
                            try (var client = ZmqChannel.Client.connect(address)) {
                                client.send(
                                        new Frame.Hello(
                                                Frame.PROTOCOL_VERSION, 1, Version.UNKNOWN, "."));
                                assertInstanceOf(Frame.Welcome.class, client.recv(2_000));
                            }
                        });
        var worker = ioWorker.get();
        if (worker == null) throw new AssertionError("Missing IO worker");
        assertFalse(worker.isAlive());
        assertEndpointReleased(address);
    }

    @Test
    void dependencyFailureAbortsStartupAndReleasesResources() throws Exception {
        var address = freeAddress();
        var unavailable = new IllegalStateException("command registry unavailable");
        new ApplicationContextRunner()
                .withUserConfiguration(TerminalConfiguration.class)
                .withPropertyValues("veto.terminal.bind-address=" + address)
                .withBean(AgentSessions.class, TestAgentSessions::create)
                .withBean(SessionService.class, () -> mock(SessionService.class))
                .withBean(SessionCommandService.class, () -> mock(SessionCommandService.class))
                .withBean(
                        CommandRegistry.class,
                        () -> {
                            throw unavailable;
                        },
                        definition -> definition.setDependsOn("ipcTransport"))
                .run(
                        context -> {
                            var failure =
                                    assertInstanceOf(
                                            BeanCreationException.class,
                                            context.getStartupFailure());
                            assertSame(unavailable, failure.getMostSpecificCause());
                        });
        assertEndpointReleased(address);
    }

    @Test
    void interruptedStopStillWaitsForExclusiveTransportHandoff() throws Exception {
        var receiving = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var stopping = new CountDownLatch(1);
        var stopped = new CountDownLatch(1);
        var interruptPreserved = new AtomicBoolean();
        var transport = mock(ServerTransport.class);
        when(transport.recv(anyLong()))
                .thenAnswer(
                        invocation -> {
                            receiving.countDown();
                            assertTrue(release.await(5, TimeUnit.SECONDS));
                            return null;
                        });
        var server =
                new IpcServer(
                        mock(CommandRegistry.class),
                        transport,
                        TestAgentSessions.create(),
                        mock(SessionService.class),
                        mock(SessionCommandService.class));
        server.start();
        try {
            assertTrue(receiving.await(2, TimeUnit.SECONDS));
            var closer =
                    Thread.ofVirtual()
                            .start(
                                    () -> {
                                        Thread.currentThread().interrupt();
                                        stopping.countDown();
                                        server.stop();
                                        interruptPreserved.set(
                                                Thread.currentThread().isInterrupted());
                                        stopped.countDown();
                                    });
            assertTrue(stopping.await(2, TimeUnit.SECONDS));
            assertFalse(stopped.await(100, TimeUnit.MILLISECONDS));
            release.countDown();
            assertTrue(stopped.await(2, TimeUnit.SECONDS));
            closer.join();
            assertTrue(interruptPreserved.get());
            verify(transport, never()).close();
        } finally {
            release.countDown();
            server.stop();
        }
    }

    private static @NonNull ApplicationContextRunner runner(@NonNull String address) {
        return new ApplicationContextRunner()
                .withUserConfiguration(TerminalConfiguration.class)
                .withPropertyValues("veto.terminal.bind-address=" + address)
                .withBean(CommandRegistry.class, () -> mock(CommandRegistry.class))
                .withBean(AgentSessions.class, TestAgentSessions::create)
                .withBean(SessionService.class, () -> mock(SessionService.class))
                .withBean(SessionCommandService.class, () -> mock(SessionCommandService.class))
                .withBean(EndpointConsumer.class);
    }

    private static @NonNull String freeAddress() throws Exception {
        try (var reservation = new ServerSocket(0)) {
            return "tcp://127.0.0.1:" + reservation.getLocalPort();
        }
    }

    private static void assertEndpointReleased(@NonNull String address) {
        try (var context = new ZContext();
                var transport = ZmqChannel.Server.bindRouter(context, address)) {
            assertNull(transport.recv(0));
        }
    }

    record EndpointConsumer(@NonNull ObjectProvider<IpcServer> servers) {}
}
