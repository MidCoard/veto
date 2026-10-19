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
import java.nio.channels.NotYetConnectedException;
import java.nio.channels.SocketChannel;
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
    void unconnectedChannelIsRejectedAndClosedDuringInitialization() throws Exception {
        try (var channel = SocketChannel.open()) {
            assertTrue(channel.isOpen());
            assertThrows(NotYetConnectedException.class, () -> TcpConnection.accept(channel));
            assertFalse(channel.isOpen());
        }
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
            String address = "tcp://127.0.0.1:" + listener.socket().getLocalPort();
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
            assertTrue(
                    pair.accepted().isOpen(), "Frame validation does not close an owned channel");
            pair.connection().close();
            assertFalse(pair.accepted().isOpen());
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
            assertTrue(pair.accepted().isOpen(), "Peer EOF does not release local resources");
            pair.connection().close();
            assertFalse(pair.accepted().isOpen());
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
            assertTrue(pair.accepted().isOpen(), "The owner handles a malformed frame");
            pair.connection().close();
            assertFalse(pair.accepted().isOpen());
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
    void concurrentIdempotentCloseReleasesBlockedReaderAndWriter() throws Exception {
        try (var workers = Executors.newVirtualThreadPerTaskExecutor();
                var pair = SocketPair.open()) {
            pair.accepted().socket().setSendBufferSize(1_024);
            var startedIo = new CountDownLatch(2);
            var incoming =
                    workers.submit(
                            () -> {
                                startedIo.countDown();
                                return pair.connection().receive();
                            });
            var outgoing =
                    workers.submit(
                            () -> {
                                startedIo.countDown();
                                pair.connection().send(new byte[FrameTransport.MAX_FRAME_BYTES]);
                                return null;
                            });
            assertTrue(startedIo.await(2, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> incoming.get(100, TimeUnit.MILLISECONDS));
            assertThrows(TimeoutException.class, () -> outgoing.get(100, TimeUnit.MILLISECONDS));
            var firstClose = workers.submit(pair.connection()::close);
            var secondClose = workers.submit(pair.connection()::close);
            firstClose.get(2, TimeUnit.SECONDS);
            secondClose.get(2, TimeUnit.SECONDS);
            var failure =
                    assertThrows(ExecutionException.class, () -> incoming.get(2, TimeUnit.SECONDS));
            assertInstanceOf(IOException.class, failure.getCause());
            var writeFailure =
                    assertThrows(ExecutionException.class, () -> outgoing.get(2, TimeUnit.SECONDS));
            assertInstanceOf(IOException.class, writeFailure.getCause());
            assertFalse(pair.accepted().isOpen());
            pair.peer().setSoTimeout(2_000);
            assertTrue(
                    pair.peer().getInputStream().readAllBytes().length
                            < FrameTransport.MAX_FRAME_BYTES + Integer.BYTES);
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
            assertTrue(
                    pair.accepted().isOpen(), "A deadline reports failure without owning shutdown");
            pair.connection().close();
            assertFalse(pair.accepted().isOpen());
        }
    }

    @Test
    void stalledWriteAllowsReadsAndReportsItsWriteDeadline() throws Exception {
        try (var workers = Executors.newVirtualThreadPerTaskExecutor();
                var pair = SocketPair.open()) {
            pair.accepted().socket().setSendBufferSize(1_024);
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
            assertInstanceOf(SocketTimeoutException.class, failure.getCause());
            assertTrue(System.nanoTime() - started >= TimeUnit.SECONDS.toNanos(9));
            assertTrue(pair.accepted().isOpen(), "A write timeout must be handled by the owner");
            pair.connection().close();
            assertFalse(pair.accepted().isOpen());
            assertThrows(IOException.class, pair.connection()::receive);
        }
    }

    @Test
    void rejectsBindCollision() throws Exception {
        try (var listener = TcpConnection.bind("tcp://127.0.0.1:0")) {
            assertThrows(
                    IOException.class,
                    () ->
                            TcpConnection.bind(
                                    "tcp://127.0.0.1:" + listener.socket().getLocalPort()));
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
            @NonNull Socket peer,
            @NonNull SocketChannel accepted,
            @NonNull TcpConnection connection)
            implements AutoCloseable {
        static @NonNull SocketPair open() throws IOException {
            try (var listener = TcpConnection.bind("tcp://127.0.0.1:0")) {
                var peer = new Socket();
                try {
                    peer.setReceiveBufferSize(1_024);
                    peer.setTcpNoDelay(true);
                    peer.setSoTimeout(2_000);
                    peer.connect(
                            new InetSocketAddress("127.0.0.1", listener.socket().getLocalPort()),
                            2_000);
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
