package top.focess.veto.transport.tcp;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import top.focess.veto.api.util.Resources;
import top.focess.veto.contract.Frame;
import top.focess.veto.contract.FrameCodec;
import top.focess.veto.contract.FrameTransport;
import top.focess.veto.contract.Version;

@Timeout(25)
class TcpConnectionTest {
    @Test
    void setupFailureClosesTheOwnedSocketAndPreservesBothFailures() {
        var setupFailure = new IllegalStateException("Socket setup failed");
        var cleanupFailure = new IOException("Socket close failed");
        var socket =
                new Socket() {
                    @Override
                    public void setTcpNoDelay(boolean enabled) {
                        throw setupFailure;
                    }

                    @Override
                    public void close() throws IOException {
                        throw cleanupFailure;
                    }
                };
        var failure = assertThrows(IllegalStateException.class, () -> TcpConnection.accept(socket));
        assertSame(setupFailure, failure);
        assertArrayEquals(new Throwable[] {cleanupFailure}, failure.getSuppressed());
    }

    @Test
    void unresolvedBindHostDoesNotFallBackToAllInterfaces() {
        assertThrows(
                UnknownHostException.class,
                () -> TcpConnection.bind("tcp://veto-bind-test.invalid:0"));
    }

    @Test
    void roundTripPreservesBothDirectionsAcrossRepeatedConnections() throws Exception {
        try (var listener = TcpConnection.bind("tcp://127.0.0.1:0")) {
            String address = "tcp://127.0.0.1:" + listener.getLocalPort();
            for (int sequence = 1; sequence <= 100; sequence++) {
                try (var client = TcpConnection.connect(address);
                        var server = TcpConnection.accept(listener.accept())) {
                    var hello =
                            new Frame.Hello(Frame.PROTOCOL_VERSION, sequence, Version.UNKNOWN, "");
                    client.send(hello);
                    assertEquals(hello, server.receive());
                    var welcome =
                            new Frame.Welcome(Frame.PROTOCOL_VERSION, sequence, Version.UNKNOWN);
                    server.send(welcome);
                    assertEquals(welcome, client.receive());
                }
            }
        }
    }

    @Test
    void readsHeaderAndUtf8BodyAcrossPartialWrites() throws Exception {
        try (var workers = Executors.newVirtualThreadPerTaskExecutor();
                var pair = SocketPair.open()) {
            var expected = new Frame.Request(2, null, null, "fragmented 中文 🚀");
            var bytes = packet(expected);
            var incoming = workers.submit(pair.connection()::receive);
            var output = pair.peer().getOutputStream();
            output.write(bytes, 0, 1);
            output.flush();
            assertThrows(TimeoutException.class, () -> incoming.get(100, TimeUnit.MILLISECONDS));
            output.write(bytes, 1, 2);
            output.flush();
            assertThrows(TimeoutException.class, () -> incoming.get(100, TimeUnit.MILLISECONDS));
            output.write(bytes, 3, bytes.length - 5);
            output.flush();
            assertThrows(TimeoutException.class, () -> incoming.get(100, TimeUnit.MILLISECONDS));
            output.write(bytes, bytes.length - 2, 2);
            output.flush();
            assertEquals(expected, incoming.get(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void separatesCoalescedFramesAndWritesBigEndianLengthWithoutChangingPayload() throws Exception {
        try (var pair = SocketPair.open()) {
            var first = new Frame.Heartbeat();
            var second = new Frame.Request(3, null, null, "/help");
            var bytes = new ByteArrayOutputStream();
            bytes.write(packet(first));
            bytes.write(packet(second));
            pair.peer().getOutputStream().write(bytes.toByteArray());
            assertEquals(first, pair.connection().receive());
            assertEquals(second, pair.connection().receive());

            var expected = new Frame.Welcome(Frame.PROTOCOL_VERSION, 1, Version.UNKNOWN);
            pair.connection().send(expected);
            var input = new DataInputStream(pair.peer().getInputStream());
            var payload = FrameCodec.encode(expected);
            assertEquals(payload.length, input.readInt());
            assertArrayEquals(payload, input.readNBytes(payload.length));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE, 1_048_577, Integer.MAX_VALUE})
    void rejectsInvalidLengthWithoutWaitingForOrAllocatingItsPayload(int length) throws Exception {
        try (var workers = Executors.newVirtualThreadPerTaskExecutor();
                var pair = SocketPair.open()) {
            var output = new DataOutputStream(pair.peer().getOutputStream());
            output.writeInt(length);
            output.flush();
            var incoming = workers.submit(pair.connection()::receive);
            var failure =
                    assertThrows(ExecutionException.class, () -> incoming.get(2, TimeUnit.SECONDS));
            assertInstanceOf(IOException.class, failure.getCause());
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 3, 4, 5})
    void rejectsEofBeforeOrInsideAFrame(int bytesToSend) throws Exception {
        try (var pair = SocketPair.open()) {
            var bytes = packet(new Frame.Heartbeat());
            pair.peer().getOutputStream().write(bytes, 0, bytesToSend);
            pair.peer().shutdownOutput();
            assertThrows(EOFException.class, pair.connection()::receive);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "{}", "null", "{\"type\":\"unknown\"}"})
    void rejectsInvalidJsonOrUnknownFrame(@NonNull String json) throws Exception {
        try (var pair = SocketPair.open()) {
            var payload = json.getBytes(StandardCharsets.UTF_8);
            var output = new DataOutputStream(pair.peer().getOutputStream());
            output.writeInt(payload.length);
            output.write(payload);
            output.flush();
            assertThrows(IOException.class, pair.connection()::receive);
        }
    }

    @Test
    void rejectsOversizedOutgoingFrameBeforeWritingAnyBytes() throws Exception {
        try (var pair = SocketPair.open()) {
            assertThrows(IOException.class, () -> pair.connection().send(new byte[0]));
            assertThrows(
                    IOException.class,
                    () -> pair.connection().send(new byte[FrameTransport.MAX_FRAME_BYTES + 1]));
            var expected = new Frame.Heartbeat();
            pair.connection().send(expected);
            var input = new DataInputStream(pair.peer().getInputStream());
            assertEquals(expected, FrameCodec.decode(input.readNBytes(input.readInt())));
        }
    }

    @Test
    void concurrentIdempotentCloseReleasesBlockedReader() throws Exception {
        try (var workers = Executors.newVirtualThreadPerTaskExecutor();
                var pair = SocketPair.open()) {
            var reading = new CountDownLatch(1);
            var incoming =
                    workers.submit(
                            () -> {
                                reading.countDown();
                                return pair.connection().receive();
                            });
            assertTrue(reading.await(2, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> incoming.get(100, TimeUnit.MILLISECONDS));
            var firstClose = workers.submit(pair.connection()::close);
            var secondClose = workers.submit(pair.connection()::close);
            firstClose.get(2, TimeUnit.SECONDS);
            secondClose.get(2, TimeUnit.SECONDS);
            var failure =
                    assertThrows(ExecutionException.class, () -> incoming.get(2, TimeUnit.SECONDS));
            assertInstanceOf(IOException.class, failure.getCause());
            pair.peer().setSoTimeout(2_000);
            assertEquals(-1, pair.peer().getInputStream().read());
        }
    }

    @Test
    void partialFrameDeadlineDoesNotRestartWhenMoreBytesArrive() throws Exception {
        try (var workers = Executors.newVirtualThreadPerTaskExecutor();
                var timer =
                        Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory());
                var pair = SocketPair.open()) {
            pair.peer().getOutputStream().write(0);
            pair.peer().getOutputStream().flush();
            long started = System.nanoTime();
            var incoming = workers.submit(pair.connection()::receive);
            var progress =
                    timer.schedule(
                            () -> {
                                pair.peer().getOutputStream().write(0);
                                pair.peer().getOutputStream().flush();
                                return null;
                            },
                            5,
                            TimeUnit.SECONDS);
            progress.get(7, TimeUnit.SECONDS);
            var failure =
                    assertThrows(ExecutionException.class, () -> incoming.get(7, TimeUnit.SECONDS));
            assertInstanceOf(SocketTimeoutException.class, failure.getCause());
            assertTrue(System.nanoTime() - started >= TimeUnit.SECONDS.toNanos(9));
        }
    }

    @Test
    void stalledWriteAllowsReadsAndClosesAtItsWriteDeadline() throws Exception {
        try (var workers = Executors.newVirtualThreadPerTaskExecutor();
                var pair = SocketPair.open()) {
            pair.accepted().setSendBufferSize(1_024);
            var writing = new CountDownLatch(1);
            long started = System.nanoTime();
            var outgoing =
                    workers.submit(
                            () -> {
                                writing.countDown();
                                pair.connection().send(new byte[FrameTransport.MAX_FRAME_BYTES]);
                                return null;
                            });
            assertTrue(writing.await(2, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> outgoing.get(100, TimeUnit.MILLISECONDS));

            var hello = new Frame.Hello(Frame.PROTOCOL_VERSION, 1, Version.UNKNOWN, "");
            pair.peer().getOutputStream().write(packet(hello));
            var incoming = workers.submit(pair.connection()::receive);
            assertEquals(hello, incoming.get(2, TimeUnit.SECONDS));
            assertFalse(outgoing.isDone(), "Receiving must progress while the writer is blocked");

            var failure =
                    assertThrows(
                            ExecutionException.class, () -> outgoing.get(15, TimeUnit.SECONDS));
            assertInstanceOf(IOException.class, failure.getCause());
            assertTrue(System.nanoTime() - started >= TimeUnit.SECONDS.toNanos(9));
            assertThrows(IOException.class, pair.connection()::receive);
        }
    }

    @Test
    void rejectsBindCollision() throws Exception {
        try (var listener = TcpConnection.bind("tcp://127.0.0.1:0")) {
            assertThrows(
                    IOException.class,
                    () -> TcpConnection.bind("tcp://127.0.0.1:" + listener.getLocalPort()));
        }
    }

    @Test
    void reportsRefusedConnectionWithoutReleasingTheReservedPort() throws Exception {
        try (var unavailable = new Socket()) {
            unavailable.bind(new InetSocketAddress("127.0.0.1", 0));
            assertThrows(
                    IOException.class,
                    () -> TcpConnection.connect("tcp://127.0.0.1:" + unavailable.getLocalPort()));
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "http://127.0.0.1:5555",
                "tcp://127.0.0.1",
                "tcp://127.0.0.1:65536",
                "tcp://user@127.0.0.1:5555",
                "tcp://127.0.0.1:5555/path",
                "tcp://127.0.0.1:5555?query",
                "tcp://127.0.0.1:5555#fragment",
                "tcp:///5555"
            })
    void rejectsAddressesThatAreNotPlainTcpEndpoints(@NonNull String address) {
        assertThrows(IllegalArgumentException.class, () -> TcpConnection.connect(address));
        assertThrows(IllegalArgumentException.class, () -> TcpConnection.bind(address));
    }

    @Test
    void rejectsZeroPortForClients() {
        assertThrows(
                IllegalArgumentException.class, () -> TcpConnection.connect("tcp://127.0.0.1:0"));
    }

    private static byte @NonNull [] packet(@NonNull Frame frame) throws IOException {
        var payload = FrameCodec.encode(frame);
        var bytes = new ByteArrayOutputStream(payload.length + 4);
        var output = new DataOutputStream(bytes);
        output.writeInt(payload.length);
        output.write(payload);
        return bytes.toByteArray();
    }

    private record SocketPair(
            @NonNull Socket peer, @NonNull Socket accepted, @NonNull TcpConnection connection)
            implements AutoCloseable {
        static @NonNull SocketPair open() throws IOException {
            try (var listener = TcpConnection.bind("tcp://127.0.0.1:0")) {
                var peer = new Socket();
                try {
                    peer.setReceiveBufferSize(1_024);
                    peer.setTcpNoDelay(true);
                    peer.setSoTimeout(2_000);
                    peer.connect(
                            new InetSocketAddress("127.0.0.1", listener.getLocalPort()), 2_000);
                    var accepted = listener.accept();
                    return new SocketPair(peer, accepted, TcpConnection.accept(accepted));
                } catch (Throwable failure) {
                    Resources.closeOnFailure(peer, failure);
                    throw failure;
                }
            }
        }

        @Override
        public void close() throws IOException {
            connection.close();
            peer.close();
        }
    }
}
