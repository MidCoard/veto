package top.focess.veto.terminal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;
import top.focess.veto.agent.AgentService;
import top.focess.veto.command.CommandRegistry;
import top.focess.veto.contract.IpcCodec;
import top.focess.veto.contract.IpcFrame;

class IpcPeerIsolationTest {
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
            assertTrue(dealer.send("{\"type\":\"heartbeat\"}"));
            assertInstanceOf(IpcFrame.Terminate.class, receive(dealer));
            assertTrue(
                    dealer.send(
                            "{\"type\":\"hello\",\"version\":1,\"seq\":1,"
                                    + "\"productVersion\":\"0.0.0-unknown\",\"cwd\":\".\"}"));
            assertInstanceOf(IpcFrame.Welcome.class, receive(dealer));
            assertTrue(dealer.send("{malformed json"));
            assertTrue(dealer.send("{\"type\":\"complete\",\"raw\":\"explode\",\"seq\":2}"));
            var rejected = receive(dealer);
            assertTrue(rejected instanceof IpcFrame.Error error && error.seq() == 2);
            assertTrue(dealer.send("{\"type\":\"complete\",\"raw\":\"healthy\",\"seq\":3}"));
            var healthy = receive(dealer);
            assertTrue(healthy instanceof IpcFrame.CompleteResult result && result.seq() == 3);
            verify(registry).complete(any(), eq("healthy"));
        } finally {
            server.stop();
        }
    }

    private static @NonNull IpcFrame receive(ZMQ.@NonNull Socket dealer) {
        byte[] payload = dealer.recv();
        if (payload == null) throw new AssertionError("server did not respond");
        var frame = IpcCodec.decode(payload);
        if (frame == null) throw new AssertionError("server sent an invalid frame");
        return frame;
    }
}
