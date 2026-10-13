package top.focess.veto.terminal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.net.ServerSocket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.zeromq.ZContext;
import top.focess.veto.agent.AgentService;
import top.focess.veto.bus.DeltaBroker;
import top.focess.veto.command.CommandRegistry;
import top.focess.veto.contract.Frame;
import top.focess.veto.contract.ServerTransport;
import top.focess.veto.contract.Version;
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
        var broker = mock(DeltaBroker.class);
        try (var occupied = new ServerSocket(0)) {
            runner("tcp://127.0.0.1:" + occupied.getLocalPort(), broker)
                    .run(
                            context -> {
                                assertInstanceOf(
                                        BeanCreationException.class, context.getStartupFailure());
                                verify(broker, never()).subscribeAll(any());
                            });
        }
    }

    @Test
    void springPublishesResourceBeansAndJoinsTheWorkerBeforeClosingTheSubscription()
            throws Exception {
        var address = freeAddress();
        var owner = new AtomicReference<Thread>();
        var ioWorker = new AtomicReference<Thread>();
        var closes = new AtomicInteger();
        var broker = mock(DeltaBroker.class);
        when(broker.subscribeAll(any()))
                .thenAnswer(
                        invocation -> {
                            owner.set(Thread.currentThread());
                            return (AutoCloseable)
                                    () -> {
                                        var worker = ioWorker.get();
                                        if (worker == null)
                                            throw new AssertionError("Missing IO worker");
                                        assertFalse(
                                                worker.isAlive(),
                                                "Stop IO before destroying dependencies");
                                        closes.incrementAndGet();
                                    };
                        });
        runner(address, broker)
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
                            assertTrue(context.getBean("ipcSubscription") instanceof AutoCloseable);
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
        assertEquals(Thread.currentThread(), owner.get());
        var worker = ioWorker.get();
        if (worker == null) throw new AssertionError("Missing IO worker");
        assertFalse(worker.isAlive());
        assertEquals(1, closes.get());
        assertEndpointReleased(address);
    }

    @Test
    void subscriptionFailureAbortsStartupAndReleasesTheBoundEndpoint() throws Exception {
        var address = freeAddress();
        var owner = new AtomicReference<Thread>();
        var broker = mock(DeltaBroker.class);
        var unavailable = new IllegalStateException("subscription unavailable");
        when(broker.subscribeAll(any()))
                .thenAnswer(
                        invocation -> {
                            owner.set(Thread.currentThread());
                            throw unavailable;
                        });
        runner(address, broker)
                .run(
                        context -> {
                            var failure = context.getStartupFailure();
                            if (failure == null)
                                throw new AssertionError("Subscription failure was swallowed");
                            Throwable root = failure;
                            while (root.getCause() instanceof Throwable cause) root = cause;
                            assertSame(unavailable, root);
                            assertEquals(Thread.currentThread(), owner.get());
                            assertEndpointReleased(address);
                        });
    }

    @Test
    void fatalInitializationErrorsPropagateAndReleaseResources() throws Exception {
        var address = freeAddress();
        var broker = mock(DeltaBroker.class);
        var fatal = new AssertionError("fatal initialization error");
        when(broker.subscribeAll(any())).thenThrow(fatal);
        runner(address, broker)
                .run(
                        context -> {
                            var failure = context.getStartupFailure();
                            if (failure == null)
                                throw new AssertionError("Fatal startup error was swallowed");
                            Throwable root = failure;
                            while (root.getCause() instanceof Throwable cause) root = cause;
                            assertSame(fatal, root);
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
                new IpcServer(mock(CommandRegistry.class), mock(AgentService.class), transport);
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

    private static @NonNull ApplicationContextRunner runner(
            @NonNull String address, @NonNull DeltaBroker broker) {
        return new ApplicationContextRunner()
                .withUserConfiguration(TerminalConfiguration.class)
                .withPropertyValues("veto.terminal.bind-address=" + address)
                .withBean(CommandRegistry.class, () -> mock(CommandRegistry.class))
                .withBean(AgentService.class, () -> mock(AgentService.class))
                .withBean(DeltaBroker.class, () -> broker)
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
