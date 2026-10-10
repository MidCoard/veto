package top.focess.veto.contract;

import static org.junit.jupiter.api.Assertions.*;
import static top.focess.veto.contract.ContractTestSupport.assertThrows;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class ProtocolClientFailureCleanupTest {
    @Test
    void rejectedHandshakeClosesTransportAndPreservesTheOriginalFailure() {
        var handshakeFailure = new IllegalStateException("handshake rejected");
        var cleanupFailure = new IllegalStateException("cleanup failed");
        var closes = new CountDownLatch(1);
        var caller = Thread.currentThread();
        var transport =
                new ClientTransport() {
                    public void send(Frame.@NonNull ClientFrame frame) {
                        throw handshakeFailure;
                    }

                    public Frame.ServerFrame recv(long timeoutMillis) {
                        return null;
                    }

                    public void close() {
                        assertNotSame(caller, Thread.currentThread());
                        assertTrue(Thread.currentThread().isVirtual());
                        closes.countDown();
                        throw cleanupFailure;
                    }
                };
        var failure =
                assertThrows(
                        IllegalStateException.class, () -> new ProtocolClient(() -> transport));
        assertSame(handshakeFailure, failure);
        assertArrayEquals(new Throwable[] {cleanupFailure}, failure.getSuppressed());
        assertEquals(0, closes.getCount());
    }

    @Test
    @Timeout(10)
    void receiveFailureClosesOnTheVirtualIoOwner() throws Exception {
        var failReceive = new CountDownLatch(1);
        var closed = new CountDownLatch(1);
        var transport =
                new ClientTransport() {
                    private boolean welcomed;

                    public void send(Frame.@NonNull ClientFrame frame) {}

                    public Frame.ServerFrame recv(long timeoutMillis) {
                        if (!welcomed) {
                            welcomed = true;
                            return new Frame.Welcome(Frame.PROTOCOL_VERSION, 1, Version.UNKNOWN);
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
                        assertEquals("protocol-io", Thread.currentThread().getName());
                        closed.countDown();
                    }
                };
        try (var client = new ProtocolClient(() -> transport)) {
            Field ioField = ProtocolClient.class.getDeclaredField("ioThread");
            ioField.setAccessible(true);
            if (!(ioField.get(client) instanceof Thread io))
                throw new AssertionError("IO worker was not started");
            assertTrue(io.isVirtual(), "connections must not allocate platform IO threads");
            failReceive.countDown();
            assertTrue(closed.await(5, TimeUnit.SECONDS));
            io.join(1_000);
            assertFalse(io.isAlive(), "failed connection must not retain its IO worker");
            assertTrue(client.isClosed());
        } finally {
            failReceive.countDown();
        }
    }
}
