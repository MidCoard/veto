package top.focess.veto.contract;

import static org.junit.jupiter.api.Assertions.*;
import static top.focess.veto.contract.ContractTestSupport.assertThrows;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class IpcFailureCleanupTest {
    @Test
    void rejectedHandshakeClosesTransportAndPreservesTheOriginalFailure() {
        var handshakeFailure = new IllegalStateException("handshake rejected");
        var cleanupFailure = new IllegalStateException("cleanup failed");
        var closes = new CountDownLatch(1);
        var caller = Thread.currentThread();
        var transport =
                new ClientTransport() {
                    public void send(IpcFrame.@NonNull ClientFrame frame) {
                        throw handshakeFailure;
                    }

                    public Transport.FramedMsg recv(long timeoutMillis) {
                        return null;
                    }

                    public void close() {
                        assertSame(caller, Thread.currentThread());
                        closes.countDown();
                        throw cleanupFailure;
                    }
                };
        var failure = assertThrows(IllegalStateException.class, () -> new IpcClient(transport));
        assertSame(handshakeFailure, failure);
        assertArrayEquals(new Throwable[] {cleanupFailure}, failure.getSuppressed());
        assertEquals(0, closes.getCount());
    }

    @Test
    @Timeout(10)
    void receiveFailureClosesOnTheIoOwnerAndStopsHeartbeat() throws Exception {
        var failReceive = new CountDownLatch(1);
        var closed = new CountDownLatch(1);
        var transport =
                new ClientTransport() {
                    private boolean welcomed;

                    public void send(IpcFrame.@NonNull ClientFrame frame) {}

                    public Transport.FramedMsg recv(long timeoutMillis) {
                        if (!welcomed) {
                            welcomed = true;
                            return new Transport.FramedMsg(
                                    "",
                                    new IpcFrame.Welcome(
                                            IpcFrame.PROTOCOL_VERSION, 1, Version.UNKNOWN));
                        }
                        try {
                            if (!failReceive.await(5, TimeUnit.SECONDS))
                                throw new AssertionError("test did not release receive");
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(interrupted);
                        }
                        throw new IllegalStateException("receive failed");
                    }

                    public void close() {
                        assertEquals("ipc-io", Thread.currentThread().getName());
                        closed.countDown();
                    }
                };
        try (var client = new IpcClient(transport)) {
            Field heartbeatField = IpcClient.class.getDeclaredField("heartbeatThread");
            heartbeatField.setAccessible(true);
            if (!(heartbeatField.get(client) instanceof Thread heartbeat))
                throw new AssertionError("heartbeat was not started");
            failReceive.countDown();
            assertTrue(closed.await(5, TimeUnit.SECONDS));
            heartbeat.join(1_000);
            assertFalse(heartbeat.isAlive(), "failed connection must not retain its heartbeat");
            assertTrue(client.isClosed());
        } finally {
            failReceive.countDown();
        }
    }
}
