package top.focess.veto.bus;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import jakarta.websocket.Session;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import top.focess.veto.model.SessionEntity;
import top.focess.veto.model.SessionRepository;
import top.focess.veto.session.SessionService;
import top.focess.veto.session.TestAgentSessions;
import top.focess.veto.vault.LoginSessionManager;
import top.focess.veto.vault.TestUsers;

@Timeout(15)
class WebSocketOutputTest {
    private final @NonNull List<@NonNull WebSocketWriter> connections = new ArrayList<>();
    private final @NonNull CountDownLatch release = new CountDownLatch(1);

    @AfterEach
    void stopWriters() throws Exception {
        release.countDown();
        for (var connection : connections) connection.close(CloseStatus.NORMAL);
        for (var connection : connections)
            assertTrue(connection.awaitTermination(Duration.ofSeconds(2)));
    }

    @Test
    void slowSocketDoesNotBlockEventPublisherOrHealthyPeer() throws Exception {
        var tokens = new LoginSessionManager();
        var token = tokens.createLoginSession(TestUsers.ALICE, "alice");
        var sessions = mock(SessionRepository.class);
        var session = new SessionEntity(TestUsers.ALICE, "work");
        when(sessions.findById(session.getId())).thenReturn(Optional.of(session));
        var runtime = TestAgentSessions.create(sessions, mock(AgentService.class));
        var handler =
                new VetoWebSocketHandler(
                        mock(SessionService.class),
                        mock(SessionCommandService.class),
                        tokens,
                        runtime);
        var slow = socket("slow", token);
        var healthy = socket("healthy", token);
        var publisher = runtime.session(new Scope.SessionScope(TestUsers.ALICE, session.getId()));
        var entered = new CountDownLatch(1);
        var virtual = new AtomicBoolean();
        try (var publishers = Executors.newVirtualThreadPerTaskExecutor()) {
            try {
                connect(handler, slow);
                connect(handler, healthy);
                clearInvocations(slow, healthy);
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
                var event =
                        new Delta(
                                new Scope.SessionScope(TestUsers.ALICE, session.getId()),
                                new Delta.Message("output", 0));
                publishers.submit(() -> publisher.send(event)).get(2, TimeUnit.SECONDS);
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                publishers.submit(() -> publisher.send(event)).get(2, TimeUnit.SECONDS);
                verify(healthy, timeout(2_000).times(2)).sendMessage(any(TextMessage.class));
                assertTrue(virtual.get());
                assertEquals(
                        1,
                        release.getCount(),
                        "Healthy delivery must finish while slow IO is still blocked");
            } finally {
                release.countDown();
                handler.stop();
            }
        }
    }

    @Test
    void writerDrainsInOrderBeforeProtocolCloseAndRejectsLaterOutput() throws Exception {
        var socket = socket("ordered", "token");
        var delivered = new CopyOnWriteArrayList<String>();
        var virtual = new AtomicBoolean(true);
        doAnswer(
                        invocation -> {
                            virtual.set(virtual.get() && Thread.currentThread().isVirtual());
                            TextMessage message = invocation.getArgument(0);
                            if (message == null) throw new AssertionError("Missing output message");
                            delivered.add(message.getPayload());
                            return null;
                        })
                .when(socket)
                .sendMessage(any(TextMessage.class));
        var connection = connection(socket, new AtomicBoolean(true));
        for (int i = 0; i < 128; i++) connection.send(new TextMessage(Integer.toString(i)));
        connection.finish(CloseStatus.PROTOCOL_ERROR);
        connection.send(new TextMessage("too late"));
        assertTrue(connection.awaitTermination(Duration.ofSeconds(2)));
        assertEquals(128, delivered.size());
        for (int i = 0; i < 128; i++) assertEquals(Integer.toString(i), delivered.get(i));
        assertTrue(virtual.get());
        verify(socket).close(CloseStatus.PROTOCOL_ERROR);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void slowPeerCannotExceedMessageOrUtf8ByteLimit(boolean byteLimit) throws Exception {
        var socket = socket("bounded", "token");
        var entered = block(socket);
        var connection = connection(socket, new AtomicBoolean(true));
        connection.send(new TextMessage("in flight"));
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        var message = new TextMessage(byteLimit ? "界".repeat(200_000) : "queued");
        int admitted = byteLimit ? 1 : 256;
        for (int i = 0; i < admitted; i++) connection.send(message);
        assertTrue(connection.isOpen());
        connection.send(message);
        assertFalse(connection.isOpen());
        release.countDown();
        assertTrue(connection.awaitTermination(Duration.ofSeconds(2)));
        verify(socket, times(1)).sendMessage(any(TextMessage.class));
        verify(socket)
                .close(
                        CloseStatus.SESSION_NOT_RELIABLE.withReason(
                                "Outbound buffer limit exceeded"));
    }

    @Test
    void oversizedSingleMessageIsRejectedBeforeNetworkIo() throws Exception {
        var socket = socket("oversized", "token");
        var connection = connection(socket, new AtomicBoolean(true));
        connection.send(new TextMessage("x".repeat(1024 * 1024 + 1)));
        assertTrue(connection.awaitTermination(Duration.ofSeconds(2)));
        verify(socket, never()).sendMessage(any(TextMessage.class));
    }

    @Test
    void authenticationIsRecheckedAfterQueuedOutputWaitsBehindSlowIo() throws Exception {
        var socket = socket("revoked", "token");
        var entered = block(socket);
        var authenticated = new AtomicBoolean(true);
        var connection = connection(socket, authenticated);
        connection.send(new TextMessage("already in flight"));
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        connection.send(new TextMessage("must not leave after revocation"));
        authenticated.set(false);
        release.countDown();
        assertTrue(connection.awaitTermination(Duration.ofSeconds(2)));
        verify(socket, times(1)).sendMessage(any(TextMessage.class));
        verify(socket).close(CloseStatus.POLICY_VIOLATION.withReason("authentication expired"));
    }

    @Test
    void immediateCloseDiscardsQueuedOutputAndWakesAnIdleWriter() throws Exception {
        var socket = socket("closing", "token");
        var entered = block(socket);
        var connection = connection(socket, new AtomicBoolean(true));
        connection.send(new TextMessage("in flight"));
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        connection.send(new TextMessage("discard"));
        connection.close(CloseStatus.GOING_AWAY);
        connection.send(new TextMessage("rejected"));
        release.countDown();
        assertTrue(connection.awaitTermination(Duration.ofSeconds(2)));
        verify(socket, times(1)).sendMessage(any(TextMessage.class));
        var idle = connection(socket("idle", "token"), new AtomicBoolean(true));
        idle.close(CloseStatus.NORMAL);
        assertTrue(idle.awaitTermination(Duration.ofSeconds(2)));
    }

    @Test
    void writeFailureStopsTheWriterAndRemovesItsConnection() throws Exception {
        var socket = socket("failed", "token");
        doThrow(new IOException("failed")).when(socket).sendMessage(any(TextMessage.class));
        var removed = new AtomicBoolean();
        var connection = new WebSocketWriter(socket, () -> true, ignored -> removed.set(true));
        connections.add(connection);
        connection.start();
        connection.send(new TextMessage("fails"));
        assertTrue(connection.awaitTermination(Duration.ofSeconds(2)));
        assertTrue(removed.get());
        assertFalse(connection.isOpen());
        verify(socket).close(CloseStatus.SERVER_ERROR);
    }

    @Test
    void noListenersAvoidsSessionLookupAndShutdownRejectsNewConnections() throws Exception {
        var sessions = mock(SessionRepository.class);
        var tokens = new LoginSessionManager();
        var runtime = TestAgentSessions.create(sessions, mock(AgentService.class));
        var handler =
                new VetoWebSocketHandler(
                        mock(SessionService.class),
                        mock(SessionCommandService.class),
                        tokens,
                        runtime);
        var scope = new Scope.SessionScope(TestUsers.ALICE, UUID.randomUUID().toString());
        runtime.session(scope).send(new Delta(scope, new Delta.Message("unobserved", 0)));
        verifyNoInteractions(sessions);
        var token = tokens.createLoginSession(TestUsers.ALICE, "alice");
        var socket = socket("idle", token);
        connect(handler, socket);
        handler.stop();
        verify(socket).close(CloseStatus.GOING_AWAY);
        assertEquals(0, handler.getActiveSessionCount());
        var late = socket("late", token);
        handler.afterConnectionEstablished(late);
        verify(late).close(CloseStatus.GOING_AWAY);
    }

    @Test
    void writerRechecksAttachmentBeforeSendingAdmittedFrame() throws Exception {
        var socket = socket("detached", "token");
        var attached = new AtomicBoolean(true);
        var connection = new WebSocketWriter(socket, () -> true, ignored -> {});
        connections.add(connection);
        assertTrue(connection.send(new Frame.HeartbeatAck(Instant.EPOCH), attached::get));
        attached.set(false);
        connection.finish(CloseStatus.NORMAL);
        connection.start();
        assertTrue(connection.awaitTermination(Duration.ofSeconds(2)));
        verify(socket, never()).sendMessage(any(TextMessage.class));
    }

    private @NonNull WebSocketWriter connection(
            @NonNull WebSocketSession socket, @NonNull AtomicBoolean authenticated) {
        var connection = new WebSocketWriter(socket, authenticated::get, ignored -> {});
        connections.add(connection);
        connection.start();
        return connection;
    }

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

    private static void connect(
            @NonNull VetoWebSocketHandler handler, @NonNull WebSocketSession socket)
            throws Exception {
        handler.afterConnectionEstablished(socket);
        handler.handleTextMessage(
                socket,
                new TextMessage(
                        FrameCodec.encodeString(
                                new Frame.Hello(Frame.PROTOCOL_VERSION, 1, Version.UNKNOWN, ""))));
        verify(socket, timeout(2_000)).sendMessage(any(TextMessage.class));
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
