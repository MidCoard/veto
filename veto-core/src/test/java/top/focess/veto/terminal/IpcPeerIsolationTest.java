package top.focess.veto.terminal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;
import top.focess.veto.agent.AgentService;
import top.focess.veto.command.CommandRegistry;
import top.focess.veto.command.PromptHandler;
import top.focess.veto.command.VetoCommandSender;
import top.focess.veto.contract.Frame;
import top.focess.veto.contract.FrameCodec;
import top.focess.veto.event.EventManager;
import top.focess.veto.vault.AuthLifecycleManager;
import top.focess.veto.vault.CurrentUser;
import top.focess.veto.vault.KeysteadVault;
import top.focess.veto.vault.LoginSessionManager;
import top.focess.veto.vault.TestUsers;

class IpcPeerIsolationTest {
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
        when(registry.dispatch(any(), anyString()))
                .thenAnswer(
                        invocation -> {
                            VetoCommandSender sender = invocation.getArgument(0);
                            if (sender == null) throw new AssertionError("Missing command sender");
                            String raw = invocation.getArgument(1);
                            if ("login".equals(raw)) sender.setUser(alice);
                            else if ("prompt".equals(raw))
                                assertNull(sender.input("Confirm action", false));
                            return new Frame.Done(Map.of("loggedIn", sender.isLoggedIn()), null);
                        });
        var server = new IpcServer(registry, mock(AgentService.class), address);
        server.start();
        try (var context = new ZContext()) {
            var terminal = connect(context, address, "pending-input");
            assertEquals(true, request(terminal, "login").meta().get("loggedIn"));
            assertTrue(terminal.send("{\"type\":\"request\",\"raw\":\"prompt\"}"));
            assertInstanceOf(Frame.Prompt.class, receive(terminal));
            server.revokeUser(TestUsers.ALICE);
            assertEquals(
                    false,
                    assertInstanceOf(Frame.Done.class, receive(terminal)).meta().get("loggedIn"));
            assertEquals(true, request(terminal, "login").meta().get("loggedIn"));
        } finally {
            server.stop();
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
                            return new Frame.Done(Map.of("loggedIn", sender.isLoggedIn()), null);
                        });
        when(registry.complete(any(), anyString())).thenReturn(List.of());
        var server = new IpcServer(registry, mock(AgentService.class), address);
        var beans = new StaticListableBeanFactory(Map.of("ipcServer", server));
        var lifecycle =
                new AuthLifecycleManager(
                        mock(KeysteadVault.class),
                        mock(PromptHandler.class),
                        mock(EventManager.class),
                        new LoginSessionManager(),
                        beans.getBeanProvider(IpcServer.class));
        server.start();
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
            assertEquals(false, queued.meta().get("loggedIn"));
            assertEquals(false, request(second, "probe").meta().get("loggedIn"));
            assertEquals(true, request(other, "probe").meta().get("loggedIn"));
        } finally {
            release.countDown();
            server.stop();
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
                        "{\"type\":\"hello\",\"version\":2,\"seq\":1,"
                                + "\"productVersion\":\"0.0.0-unknown\",\"cwd\":\".\"}"));
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
        when(registry.complete(any(), eq("explode")))
                .thenThrow(new IllegalArgumentException("invalid completion syntax"));
        when(registry.complete(any(), eq("healthy"))).thenReturn(List.of());
        var server = new IpcServer(registry, mock(AgentService.class), address);
        server.start();
        try (var context = new ZContext()) {
            var dealer = context.createSocket(SocketType.DEALER);
            if (dealer == null) throw new AssertionError("DEALER creation failed");
            dealer.setIdentity("x".getBytes(StandardCharsets.UTF_8));
            dealer.setReceiveTimeOut(3_000);
            dealer.connect(address);
            assertTrue(dealer.send("{\"type\":\"heartbeat\",\"seq\":0}"));
            assertInstanceOf(Frame.Terminate.class, receive(dealer));
            assertTrue(
                    dealer.send(
                            "{\"type\":\"hello\",\"version\":2,\"seq\":1,"
                                    + "\"productVersion\":\"0.0.0-unknown\",\"cwd\":\".\"}"));
            assertInstanceOf(Frame.Welcome.class, receive(dealer));
            assertTrue(dealer.send("{\"type\":\"heartbeat\",\"seq\":9}"));
            assertEquals(9, assertInstanceOf(Frame.HeartbeatAck.class, receive(dealer)).seq());
            assertTrue(dealer.send("{malformed json"));
            assertTrue(dealer.send("{\"type\":\"complete\",\"raw\":\"explode\",\"seq\":2}"));
            var rejected = receive(dealer);
            assertTrue(rejected instanceof Frame.Error error && error.seq() == 2);
            assertTrue(dealer.send("{\"type\":\"complete\",\"raw\":\"healthy\",\"seq\":3}"));
            var healthy = receive(dealer);
            assertTrue(healthy instanceof Frame.CompleteResult result && result.seq() == 3);
            verify(registry).complete(any(), eq("healthy"));
        } finally {
            server.stop();
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
