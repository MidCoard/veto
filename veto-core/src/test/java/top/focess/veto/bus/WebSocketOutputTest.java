package top.focess.veto.bus;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import jakarta.websocket.Session;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.adapter.standard.StandardWebSocketSession;
import top.focess.veto.agent.AgentService;
import top.focess.veto.api.plugin.Scope;
import top.focess.veto.command.SessionCommandService;
import top.focess.veto.contract.Delta;
import top.focess.veto.contract.Frame;
import top.focess.veto.contract.FrameCodec;
import top.focess.veto.contract.Version;
import top.focess.veto.model.SessionRepository;
import top.focess.veto.session.AgentSessions;
import top.focess.veto.session.ProtocolConnection;
import top.focess.veto.session.SessionService;
import top.focess.veto.session.TestAgentSessions;
import top.focess.veto.vault.LoginSessionManager;
import top.focess.veto.vault.TestUsers;

@Timeout(15)
class WebSocketOutputTest {
    private final @NonNull List<@NonNull WebSocketServer> servers = new ArrayList<>();
    private final @NonNull CountDownLatch release = new CountDownLatch(1);

    @AfterEach
    void stopServers() {
        release.countDown();
        for (var server : servers) server.stop();
    }

    @Test
    void slowSocketDoesNotBlockEventPublisherOrHealthyPeer() throws Exception {
        var fixture = fixture();
        var slow = connect(fixture, "slow");
        var healthy = connect(fixture, "healthy");
        var publisher = fixture.runtime().session(fixture.scope());
        var entered = new CountDownLatch(1);
        var virtual = new AtomicBoolean();
        doAnswer(
                        invocation -> {
                            virtual.set(Thread.currentThread().isVirtual());
                            entered.countDown();
                            if (!release.await(5, TimeUnit.SECONDS))
                                throw new IOException("Test peer blocked");
                            return null;
                        })
                .when(slow)
                .sendMessage(any(TextMessage.class));
        try (var publishers = Executors.newVirtualThreadPerTaskExecutor()) {
            var event = new Delta(fixture.scope(), new Delta.Message("output", 0));
            publishers.submit(() -> publisher.send(event)).get(2, TimeUnit.SECONDS);
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            publishers.submit(() -> publisher.send(event)).get(2, TimeUnit.SECONDS);
            verify(healthy, timeout(2_000).times(2)).sendMessage(any(TextMessage.class));
            assertTrue(virtual.get());
            assertEquals(
                    1,
                    release.getCount(),
                    "Healthy delivery must finish while slow IO is still blocked");
        }
    }

    @Test
    void serverDrainsProtocolRepliesInOrderAndRejectsLaterOutput() throws Exception {
        var fixture = fixture();
        var socket = connect(fixture, "ordered");
        var connection = connection(fixture.server(), socket);
        var entered = new CountDownLatch(1);
        var delivered = new CopyOnWriteArrayList<String>();
        var virtual = new AtomicBoolean(true);
        doAnswer(
                        invocation -> {
                            virtual.set(virtual.get() && Thread.currentThread().isVirtual());
                            TextMessage message = invocation.getArgument(0);
                            if (message == null) throw new AssertionError("Missing output message");
                            var frame =
                                    assertInstanceOf(
                                            Frame.Error.class,
                                            FrameCodec.decode(message.getPayload()));
                            delivered.add(frame.content());
                            entered.countDown();
                            if (!release.await(5, TimeUnit.SECONDS))
                                throw new IOException("Test peer blocked");
                            return null;
                        })
                .when(socket)
                .sendMessage(any(TextMessage.class));
        assertTrue(connection.send(new Frame.Error("0", 0)));
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        for (int i = 1; i < 128; i++)
            assertTrue(connection.send(new Frame.Error(Integer.toString(i), 0)));
        fixture.server()
                .handleTextMessage(
                        socket,
                        new TextMessage(
                                FrameCodec.encodeString(
                                        new Frame.Hello(
                                                Frame.PROTOCOL_VERSION, 2, Version.UNKNOWN, ""))));
        assertFalse(connection.send(new Frame.Error("too late", 0)));
        release.countDown();
        verify(socket, timeout(2_000)).close(CloseStatus.PROTOCOL_ERROR);
        fixture.server().stop();
        assertEquals(129, delivered.size());
        for (int i = 0; i < 128; i++) assertEquals(Integer.toString(i), delivered.get(i));
        assertEquals("Invalid protocol handshake", delivered.getLast());
        assertTrue(virtual.get());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void slowPeerCannotExceedMessageOrUtf8ByteLimit(boolean byteLimit) throws Exception {
        var fixture = fixture();
        var socket = connect(fixture, "bounded");
        var entered = block(socket);
        var connection = connection(fixture.server(), socket);
        assertTrue(connection.send(new Frame.Error("in flight", 0)));
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        var message = new Frame.Error(byteLimit ? "界".repeat(200_000) : "queued", 0);
        int admitted = byteLimit ? 1 : 256;
        for (int i = 0; i < admitted; i++) assertTrue(connection.send(message));
        assertEquals(1, fixture.server().getActiveSessionCount());
        assertFalse(connection.send(message));
        assertEquals(0, fixture.server().getActiveSessionCount());
        release.countDown();
        fixture.server().stop();
        verify(socket, times(1)).sendMessage(any(TextMessage.class));
        verify(socket)
                .close(
                        CloseStatus.SESSION_NOT_RELIABLE.withReason(
                                "Outbound buffer limit exceeded"));
    }

    @Test
    void oversizedSingleMessageIsRejectedBeforeNetworkIo() throws Exception {
        var fixture = fixture();
        var socket = connect(fixture, "oversized");
        assertFalse(
                connection(fixture.server(), socket)
                        .send(new Frame.Error("x".repeat(1024 * 1024 + 1), 0)));
        fixture.server().stop();
        verify(socket, never()).sendMessage(any(TextMessage.class));
        assertTrue(clients(fixture.server()).isEmpty());
    }

    @Test
    void authenticationIsRecheckedAfterQueuedOutputWaitsBehindSlowIo() throws Exception {
        var fixture = fixture();
        var socket = connect(fixture, "revoked");
        var entered = block(socket);
        var publisher = fixture.runtime().session(fixture.scope());
        publisher.send(new Delta(fixture.scope(), new Delta.Message("already in flight", 0)));
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        publisher.send(
                new Delta(
                        fixture.scope(), new Delta.Message("must not leave after revocation", 0)));
        fixture.tokens().revokeToken(fixture.token());
        release.countDown();
        verify(socket, timeout(2_000))
                .close(CloseStatus.POLICY_VIOLATION.withReason("authentication expired"));
        fixture.server().stop();
        verify(socket, times(1)).sendMessage(any(TextMessage.class));
    }

    @Test
    void immediateCloseDiscardsQueuedOutputAndWakesAnIdleWriter() throws Exception {
        var fixture = fixture();
        var socket = connect(fixture, "closing");
        var entered = block(socket);
        var connection = connection(fixture.server(), socket);
        assertTrue(connection.send(new Frame.Error("in flight", 0)));
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        assertTrue(connection.send(new Frame.Error("discard", 0)));
        fixture.server().afterConnectionClosed(socket, CloseStatus.GOING_AWAY);
        assertFalse(connection.send(new Frame.Error("rejected", 0)));
        assertEquals(0, fixture.server().getActiveSessionCount());
        release.countDown();
        verify(socket, timeout(2_000)).close(CloseStatus.GOING_AWAY);
        verify(socket, times(1)).sendMessage(any(TextMessage.class));
        var idle = connect(fixture, "idle");
        fixture.server().afterConnectionClosed(idle, CloseStatus.NORMAL);
        verify(idle, timeout(2_000)).close(CloseStatus.NORMAL);
        fixture.server().stop();
        assertTrue(clients(fixture.server()).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shutdownStillWaitsForLogicallyClosedClientsWithBlockedIo(boolean closingSocket)
            throws Exception {
        var fixture = fixture();
        var socket = connect(fixture, "closing-slowly");
        if (closingSocket) {
            var closing = new CountDownLatch(1);
            doAnswer(
                            invocation -> {
                                closing.countDown();
                                if (!release.await(5, TimeUnit.SECONDS))
                                    throw new IOException("Test close blocked");
                                return null;
                            })
                    .when(socket)
                    .close(any(CloseStatus.class));
            fixture.server().afterConnectionClosed(socket, CloseStatus.GOING_AWAY);
            assertTrue(closing.await(2, TimeUnit.SECONDS));
        } else {
            var entered = block(socket);
            assertTrue(connection(fixture.server(), socket).send(new Frame.Error("in flight", 0)));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            fixture.server().afterConnectionClosed(socket, CloseStatus.GOING_AWAY);
        }
        assertEquals(0, fixture.server().getActiveSessionCount());
        assertEquals(1, clients(fixture.server()).size());
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var shutdown = workers.submit(() -> fixture.server().stop());
            assertThrows(TimeoutException.class, () -> shutdown.get(100, TimeUnit.MILLISECONDS));
            release.countDown();
            shutdown.get(2, TimeUnit.SECONDS);
        }
        verify(socket).close(CloseStatus.GOING_AWAY);
        assertTrue(clients(fixture.server()).isEmpty());
    }

    @Test
    void writeFailureStopsTheWriterAndRemovesItsConnection() throws Exception {
        var fixture = fixture();
        var socket = connect(fixture, "failed");
        var connection = connection(fixture.server(), socket);
        doThrow(new IOException("failed")).when(socket).sendMessage(any(TextMessage.class));
        assertTrue(connection.send(new Frame.Error("fails", 0)));
        verify(socket, timeout(2_000)).close(CloseStatus.SERVER_ERROR);
        fixture.server().stop();
        assertTrue(clients(fixture.server()).isEmpty());
        assertFalse(connection.send(new Frame.Error("after failure", 0)));
        assertEquals(0, fixture.server().getActiveSessionCount());
    }

    @Test
    void socketCloseFailureStillDetachesProtocolAndRemovesTheClient() throws Exception {
        var fixture = fixture();
        var socket = connect(fixture, "close-failed");
        var connection = connection(fixture.server(), socket);
        doThrow(new IOException("close failed")).when(socket).close(any(CloseStatus.class));
        fixture.server().afterConnectionClosed(socket, CloseStatus.NORMAL);
        fixture.server().stop();
        verify(socket).close(CloseStatus.NORMAL);
        assertFalse(connection.send(new Frame.Error("after close", 0)));
        assertTrue(clients(fixture.server()).isEmpty());
        assertEquals(0, fixture.server().getActiveSessionCount());
    }

    @Test
    void noListenersAvoidsSessionLookupAndShutdownRejectsNewConnections() throws Exception {
        var repository = mock(SessionRepository.class);
        var fixture = fixture(repository);
        fixture.runtime()
                .session(fixture.scope())
                .send(new Delta(fixture.scope(), new Delta.Message("unobserved", 0)));
        verifyNoInteractions(repository);
        var socket = connect(fixture, "idle");
        fixture.server().stop();
        verify(socket).close(CloseStatus.GOING_AWAY);
        assertEquals(0, fixture.server().getActiveSessionCount());
        var late = socket("late", fixture.token());
        fixture.server().afterConnectionEstablished(late);
        verify(late).close(CloseStatus.GOING_AWAY);
    }

    @Test
    void writerRechecksAttachmentBeforeSendingAdmittedFrame() throws Exception {
        var fixture = fixture();
        var socket = connect(fixture, "detached");
        var connection = connection(fixture.server(), socket);
        var entered = block(socket);
        assertTrue(connection.send(new Frame.Error("in flight", 0)));
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        var attached = new AtomicBoolean(true);
        assertTrue(connection.send(new Frame.HeartbeatAck(Instant.EPOCH), attached::get));
        attached.set(false);
        assertTrue(connection.send(new Frame.Error("after detached output", 0)));
        release.countDown();
        verify(socket, timeout(2_000).times(2)).sendMessage(any(TextMessage.class));
        fixture.server().stop();
        verify(socket, times(2)).sendMessage(any(TextMessage.class));
    }

    private @NonNull Fixture fixture() {
        return fixture(mock(SessionRepository.class));
    }

    private @NonNull Fixture fixture(@NonNull SessionRepository repository) {
        var tokens = new LoginSessionManager();
        var token = tokens.createLoginSession(TestUsers.ALICE, "alice");
        var runtime = TestAgentSessions.create(repository, mock(AgentService.class));
        var server =
                new WebSocketServer(
                        mock(SessionService.class),
                        mock(SessionCommandService.class),
                        tokens,
                        runtime);
        servers.add(server);
        return new Fixture(
                server,
                runtime,
                tokens,
                token,
                new Scope.SessionScope(TestUsers.ALICE, UUID.randomUUID().toString()));
    }

    private record Fixture(
            @NonNull WebSocketServer server,
            @NonNull AgentSessions runtime,
            @NonNull LoginSessionManager tokens,
            @NonNull String token,
            Scope.@NonNull SessionScope scope) {}

    private @NonNull CountDownLatch block(@NonNull WebSocketSession socket) throws IOException {
        var entered = new CountDownLatch(1);
        doAnswer(
                        invocation -> {
                            entered.countDown();
                            if (!release.await(5, TimeUnit.SECONDS))
                                throw new IOException("Test peer blocked");
                            return null;
                        })
                .when(socket)
                .sendMessage(any(TextMessage.class));
        return entered;
    }

    private static @NonNull WebSocketSession connect(@NonNull Fixture fixture, @NonNull String id)
            throws Exception {
        var socket = socket(id, fixture.token());
        fixture.server().afterConnectionEstablished(socket);
        fixture.server()
                .handleTextMessage(
                        socket,
                        new TextMessage(
                                FrameCodec.encodeString(
                                        new Frame.Hello(
                                                Frame.PROTOCOL_VERSION, 1, Version.UNKNOWN, ""))));
        verify(socket, timeout(2_000)).sendMessage(any(TextMessage.class));
        clearInvocations(socket);
        return socket;
    }

    private static @NonNull Map<?, ?> clients(@NonNull WebSocketServer server) {
        var value = ReflectionTestUtils.getField(server, "connections");
        if (!(value instanceof Map<?, ?> clients))
            throw new AssertionError("Missing server clients");
        return clients;
    }

    private static @NonNull ProtocolConnection connection(
            @NonNull WebSocketServer server, @NonNull WebSocketSession socket) {
        var client = clients(server).get(socket.getId());
        if (client == null) throw new AssertionError("Client is not registered");
        var value = ReflectionTestUtils.getField(client, "connection");
        if (!(value instanceof ProtocolConnection connection))
            throw new AssertionError("Missing protocol connection");
        return connection;
    }

    private static @NonNull WebSocketSession socket(@NonNull String id, @NonNull String token) {
        var socket = mock(StandardWebSocketSession.class);
        var nativeSession = mock(Session.class);
        when(nativeSession.getUserProperties()).thenReturn(new HashMap<>());
        when(socket.getNativeSession()).thenReturn(nativeSession);
        when(socket.getId()).thenReturn(id);
        when(socket.isOpen()).thenReturn(true);
        when(socket.getAttributes())
                .thenReturn(
                        Map.of(
                                VetoWebSocketAuthInterceptor.AUTHENTICATED_USER_ATTRIBUTE,
                                TestUsers.ALICE,
                                VetoWebSocketAuthInterceptor.LOGIN_TOKEN_ATTRIBUTE,
                                token));
        return socket;
    }
}
