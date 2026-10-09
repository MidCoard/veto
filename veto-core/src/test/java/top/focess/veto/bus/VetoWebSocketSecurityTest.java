package top.focess.veto.bus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import top.focess.veto.VetoVersion;
import top.focess.veto.contract.EventFrame;
import top.focess.veto.contract.Frame;
import top.focess.veto.contract.FrameCodec;
import top.focess.veto.contract.DAGPayload;
import top.focess.veto.model.SessionEntity;
import top.focess.veto.model.SessionRepository;
import top.focess.veto.vault.LoginSessionManager;
import top.focess.veto.vault.TestUsers;
import top.focess.veto.veto.VetoGateway;

class VetoWebSocketSecurityTest {

    @Test
    void handshakeRequiresAValidSessionToken() {
        LoginSessionManager sessionManager = new LoginSessionManager();
        String token = sessionManager.createLoginSession(TestUsers.ALICE, "alice");
        VetoWebSocketAuthInterceptor interceptor = new VetoWebSocketAuthInterceptor(sessionManager);
        ServerHttpRequest validRequest = request("ws://localhost/ws?token=" + token);
        ServerHttpResponse validResponse = mock(ServerHttpResponse.class);
        Map<String, Object> attributes = new HashMap<>();

        assertTrue(
                interceptor.beforeHandshake(
                        validRequest, validResponse, mock(WebSocketHandler.class), attributes));
        assertTrue(
                TestUsers.ALICE.equals(
                        attributes.get(VetoWebSocketAuthInterceptor.AUTHENTICATED_USER_ATTRIBUTE)));

        ServerHttpResponse rejectedResponse = mock(ServerHttpResponse.class);
        assertFalse(
                interceptor.beforeHandshake(
                        request("ws://localhost/ws?token=invalid"),
                        rejectedResponse,
                        mock(WebSocketHandler.class),
                        new HashMap<>()));
        verify(rejectedResponse).setStatusCode(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void deltaFramesReachOnlyConnectionsOwnedByTheSessionUser() throws Exception {
        SessionRepository sessions = mock(SessionRepository.class);
        LoginSessionManager tokens = new LoginSessionManager();
        VetoWebSocketHandler handler =
                new VetoWebSocketHandler(mock(VetoGateway.class), sessions, tokens);
        WebSocketSession alice =
                socket(
                        "alice-socket",
                        TestUsers.ALICE,
                        tokens.createLoginSession(TestUsers.ALICE, "alice"));
        WebSocketSession bob =
                socket(
                        "bob-socket",
                        TestUsers.BOB,
                        tokens.createLoginSession(TestUsers.BOB, "bob"));
        handler.afterConnectionEstablished(alice);
        handler.afterConnectionEstablished(bob);
        clearInvocations(alice, bob);

        SessionEntity session = new SessionEntity(TestUsers.ALICE, "work");
        when(sessions.findById(session.getId())).thenReturn(Optional.of(session));
        handler.sendFrame(
                EventFrame.builder()
                        .sessionId(UUID.fromString(session.getId()))
                        .kind(EventFrame.Kind.ASSISTANT_MESSAGE)
                        .text("private")
                        .build());

        var sent = ArgumentCaptor.forClass(TextMessage.class);
        verify(alice).sendMessage(sent.capture());
        var frame = FrameCodec.decode(sent.getValue().getPayload());
        if (!(frame instanceof EventFrame decoded)) throw new AssertionError("Missing event");
        assertEquals(UUID.fromString(session.getId()), decoded.sessionId());
        assertEquals("private", decoded.text());
        verify(bob, never()).sendMessage(ArgumentMatchers.any(TextMessage.class));
    }

    @Test
    void malformedProtocolEnvelopesNeverReachTheGateway() throws Exception {
        var tokens = new LoginSessionManager();
        var token = tokens.createLoginSession(TestUsers.ALICE, "alice");
        var gateway = mock(VetoGateway.class);
        var handler = new VetoWebSocketHandler(gateway, mock(SessionRepository.class), tokens);
        var socket = socket("alice", TestUsers.ALICE, token);
        handler.afterConnectionEstablished(socket);
        clearInvocations(socket);
        handler.handleTextMessage(
                socket,
                new TextMessage(
                        "{\"type\":\"heartbeat\",\"type\":\"veto.process\",\"payload\":\"private\"}"));
        handler.handleTextMessage(
                socket, new TextMessage("{\"type\":\"veto.process\",\"payload\":\"private\"} {}"));
        verifyNoInteractions(gateway);
        var sent = ArgumentCaptor.forClass(TextMessage.class);
        verify(socket, times(2)).sendMessage(sent.capture());
        for (var message : sent.getAllValues()) {
            assertTrue(FrameCodec.decode(message.getPayload()) instanceof Frame.Error);
        }
    }

    @Test
    void sharedFramesRetainWebSocketAdmissionBoundaries() throws Exception {
        var tokens = new LoginSessionManager();
        var gateway = mock(VetoGateway.class);
        var handler = new VetoWebSocketHandler(gateway, mock(SessionRepository.class), tokens);
        var socket =
                socket(
                        "alice",
                        TestUsers.ALICE,
                        tokens.createLoginSession(TestUsers.ALICE, "alice"));
        handler.afterConnectionEstablished(socket);
        handler.handleTextMessage(
                socket, new TextMessage(FrameCodec.encodeString(new Frame.Heartbeat(7))));
        handler.handleTextMessage(
                socket, new TextMessage(FrameCodec.encodeString(new Frame.Request("/signup"))));
        var sent = ArgumentCaptor.forClass(TextMessage.class);
        verify(socket, times(3)).sendMessage(sent.capture());
        var values = sent.getAllValues();
        var first = FrameCodec.decode(values.get(0).getPayload());
        if (!(first instanceof Frame.Welcome welcome)) throw new AssertionError("Missing welcome");
        assertEquals(Frame.PROTOCOL_VERSION, welcome.version());
        var second = FrameCodec.decode(values.get(1).getPayload());
        if (!(second instanceof Frame.HeartbeatAck ack))
            throw new AssertionError("Missing heartbeat acknowledgement");
        assertEquals(7, ack.seq());
        assertTrue(FrameCodec.decode(values.get(2).getPayload()) instanceof Frame.Error);
        verifyNoInteractions(gateway);
    }

    @Test
    void helloAndSubscriptionsUseTheRealCallbackEntryPoint() throws Exception {
        var tokens = new LoginSessionManager();
        var gateway = mock(VetoGateway.class);
        var handler = new VetoWebSocketHandler(gateway, mock(SessionRepository.class), tokens);
        var socket = socket("client", TestUsers.ALICE,
                tokens.createLoginSession(TestUsers.ALICE, "alice"));
        handler.afterConnectionEstablished(socket);
        clearInvocations(socket);
        handler.handleTextMessage(socket,
                new TextMessage(FrameCodec.encodeString(new Frame.Hello(Frame.PROTOCOL_VERSION, 23, VetoVersion.VERSION, ""))));
        handler.handleTextMessage(socket,
                new TextMessage(FrameCodec.encodeString(new Frame.Subscribe("all"))));
        handler.handleTextMessage(socket,
                new TextMessage(FrameCodec.encodeString(new Frame.Unsubscribe())));
        var sent = ArgumentCaptor.forClass(TextMessage.class);
        verify(socket, times(3)).sendMessage(sent.capture());
        var replies = sent.getAllValues();
        if (!(FrameCodec.decode(replies.getFirst().getPayload()) instanceof Frame.Welcome welcome))
            throw new AssertionError("Missing welcome");
        assertEquals(23, welcome.seq());
        assertTrue(FrameCodec.decode(replies.get(1).getPayload()) instanceof Frame.Subscribed);
        assertTrue(FrameCodec.decode(replies.get(2).getPayload()) instanceof Frame.Unsubscribed);
        verifyNoInteractions(gateway);
    }

    @Test
    void serverOnlyFramesAndUnsupportedVersionsAreRejected() throws Exception {
        var tokens = new LoginSessionManager();
        var gateway = mock(VetoGateway.class);
        var handler = new VetoWebSocketHandler(gateway, mock(SessionRepository.class), tokens);
        var socket = socket("client", TestUsers.ALICE,
                tokens.createLoginSession(TestUsers.ALICE, "alice"));
        handler.afterConnectionEstablished(socket);
        clearInvocations(socket);
        handler.handleTextMessage(socket,
                new TextMessage(FrameCodec.encodeString(new Frame.Welcome(Frame.PROTOCOL_VERSION, 0, VetoVersion.VERSION))));
        handler.handleTextMessage(socket,
                new TextMessage(FrameCodec.encodeString(new Frame.Hello(Frame.PROTOCOL_VERSION + 1, 24, VetoVersion.VERSION, ""))));
        var sent = ArgumentCaptor.forClass(TextMessage.class);
        verify(socket, times(2)).sendMessage(sent.capture());
        for (var reply : sent.getAllValues())
            assertTrue(FrameCodec.decode(reply.getPayload()) instanceof Frame.Error);
        verifyNoInteractions(gateway);
    }

    @Test
    void dagBroadcastHonorsOwnerAndTopicWithoutEchoingSender() throws Exception {
        var tokens = new LoginSessionManager();
        var handler = new VetoWebSocketHandler(mock(VetoGateway.class), mock(SessionRepository.class), tokens);
        var token = tokens.createLoginSession(TestUsers.ALICE, "alice");
        var sender = socket("sender", TestUsers.ALICE, token);
        var recipient = socket("recipient", TestUsers.ALICE, token);
        var filtered = socket("filtered", TestUsers.ALICE, token);
        var foreign = socket("foreign", TestUsers.BOB, tokens.createLoginSession(TestUsers.BOB, "bob"));
        for (var socket : List.of(sender, recipient, filtered, foreign))
            handler.afterConnectionEstablished(socket);
        handler.handleTextMessage(recipient,
                new TextMessage(FrameCodec.encodeString(new Frame.Subscribe("dag.payload"))));
        handler.handleTextMessage(filtered,
                new TextMessage(FrameCodec.encodeString(new Frame.Subscribe("other"))));
        clearInvocations(sender, recipient, filtered, foreign);
        handler.handleTextMessage(sender, new TextMessage(FrameCodec.encodeString(
                new Frame.DagPayload(DAGPayload.builder().taskType("test").build(), "untrusted-source"))));
        var acknowledgement = ArgumentCaptor.forClass(TextMessage.class);
        verify(sender).sendMessage(acknowledgement.capture());
        assertTrue(FrameCodec.decode(acknowledgement.getValue().getPayload()) instanceof Frame.Received);
        var delivered = ArgumentCaptor.forClass(TextMessage.class);
        verify(recipient).sendMessage(delivered.capture());
        if (!(FrameCodec.decode(delivered.getValue().getPayload()) instanceof Frame.DagPayload dag))
            throw new AssertionError("Missing DAG payload");
        assertEquals("sender", dag.source());
        verify(filtered, never()).sendMessage(ArgumentMatchers.any(TextMessage.class));
        verify(foreign, never()).sendMessage(ArgumentMatchers.any(TextMessage.class));
    }

    @Test
    void revokedTokenCannotReceiveEventsForItsOwnUser() throws Exception {
        var tokens = new LoginSessionManager();
        var token = tokens.createLoginSession(TestUsers.ALICE, "alice");
        var sessions = mock(SessionRepository.class);
        var handler = new VetoWebSocketHandler(mock(VetoGateway.class), sessions, tokens);
        var socket = socket("revoked", TestUsers.ALICE, token);
        var event = event(sessions);
        handler.afterConnectionEstablished(socket);
        clearInvocations(socket);
        tokens.revokeToken(token);
        handler.sendFrame(event);
        assertEquals(0, handler.getActiveSessionCount());
        verify(socket, never()).sendMessage(ArgumentMatchers.any(TextMessage.class));
        verify(socket).close(ArgumentMatchers.any(CloseStatus.class));
    }

    @Test
    void failedAndClosedPeersDoNotRetainConnectionsOrBlockHealthyDelivery() throws Exception {
        var tokens = new LoginSessionManager();
        var token = tokens.createLoginSession(TestUsers.ALICE, "alice");
        var sessions = mock(SessionRepository.class);
        var handler = new VetoWebSocketHandler(mock(VetoGateway.class), sessions, tokens);
        var failed = socket("failed", TestUsers.ALICE, token);
        var closed = socket("closed", TestUsers.ALICE, token);
        var healthy = socket("healthy", TestUsers.ALICE, token);
        var event = event(sessions);
        for (var socket : List.of(failed, closed, healthy))
            handler.afterConnectionEstablished(socket);
        clearInvocations(failed, closed, healthy);
        doThrow(new IOException("send failed")).when(failed).sendMessage(ArgumentMatchers.any(TextMessage.class));
        when(closed.isOpen()).thenReturn(false);
        handler.sendFrame(event);
        assertEquals(1, handler.getActiveSessionCount());
        verify(failed).close(CloseStatus.SERVER_ERROR);
        verify(closed, never()).sendMessage(ArgumentMatchers.any(TextMessage.class));
        verify(healthy).sendMessage(ArgumentMatchers.any(TextMessage.class));
        handler.handleTransportError(healthy, new IOException("connection failed"));
        assertEquals(0, handler.getActiveSessionCount());
        handler.afterConnectionClosed(healthy, CloseStatus.SERVER_ERROR);
        verify(healthy).close(CloseStatus.SERVER_ERROR);
    }

    @Test
    void greetingFailureAndByeRemoveAdmissionBeforeLaterMessages() throws Exception {
        var tokens = new LoginSessionManager();
        var token = tokens.createLoginSession(TestUsers.ALICE, "alice");
        var gateway = mock(VetoGateway.class);
        var handler = new VetoWebSocketHandler(gateway, mock(SessionRepository.class), tokens);
        var failed = socket("failed", TestUsers.ALICE, token);
        doThrow(new IOException("greeting failed")).when(failed).sendMessage(ArgumentMatchers.any(TextMessage.class));
        handler.afterConnectionEstablished(failed);
        assertEquals(0, handler.getActiveSessionCount());
        verify(failed).close(CloseStatus.SERVER_ERROR);
        var closed = socket("bye", TestUsers.ALICE, token);
        handler.afterConnectionEstablished(closed);
        handler.handleTextMessage(closed, new TextMessage(FrameCodec.encodeString(new Frame.Bye())));
        assertEquals(0, handler.getActiveSessionCount());
        handler.handleTextMessage(closed, new TextMessage(FrameCodec.encodeString(new Frame.Process("late", null, null, null))));
        verify(closed).close(CloseStatus.NORMAL);
        verifyNoInteractions(gateway);
    }

    private static @NonNull EventFrame event(@NonNull SessionRepository sessions) {
        var session = new SessionEntity(TestUsers.ALICE, "work");
        when(sessions.findById(session.getId())).thenReturn(Optional.of(session));
        return EventFrame.builder().sessionId(UUID.fromString(session.getId()))
                .kind(EventFrame.Kind.ASSISTANT_MESSAGE).text("private").build();
    }

    private static @NonNull ServerHttpRequest request(@NonNull String uri) {
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        when(request.getHeaders()).thenReturn(new HttpHeaders());
        when(request.getURI()).thenReturn(URI.create(uri));
        return request;
    }

    @Test
    void revokedSocketCannotReadRecreatedAccountOrSubmitMessages() throws Exception {
        var tokens = new LoginSessionManager();
        var token = tokens.createLoginSession(TestUsers.ALICE, "alice");
        var sessions = mock(SessionRepository.class);
        var gateway = mock(VetoGateway.class);
        var handler = new VetoWebSocketHandler(gateway, sessions, tokens);
        var oldSocket = socket("old-alice", TestUsers.ALICE, token);
        handler.afterConnectionEstablished(oldSocket);
        clearInvocations(oldSocket);
        tokens.revokeToken(token);
        var newToken = tokens.createLoginSession(TestUsers.OWNER, "alice");
        var newSocket = socket("new-alice", TestUsers.OWNER, newToken);
        handler.afterConnectionEstablished(newSocket);
        clearInvocations(newSocket);
        var session = new SessionEntity(TestUsers.OWNER, "new-account-session");
        when(sessions.findById(session.getId())).thenReturn(Optional.of(session));
        handler.sendFrame(
                EventFrame.builder()
                        .sessionId(UUID.fromString(session.getId()))
                        .kind(EventFrame.Kind.ASSISTANT_MESSAGE)
                        .text("private-new-account-data")
                        .build());
        verify(oldSocket, never()).sendMessage(ArgumentMatchers.any(TextMessage.class));
        verify(newSocket).sendMessage(ArgumentMatchers.any(TextMessage.class));
        handler.handleTextMessage(
                oldSocket, new TextMessage("{\"type\":\"veto.process\",\"payload\":\"private\"}"));
        verify(oldSocket).close(ArgumentMatchers.any(CloseStatus.class));
        verifyNoInteractions(gateway);
    }

    private static @NonNull WebSocketSession socket(
            @NonNull String id, @NonNull UUID userId, @NonNull String token) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.isOpen()).thenReturn(true);
        when(session.getAttributes())
                .thenReturn(
                        Map.of(
                                VetoWebSocketAuthInterceptor.AUTHENTICATED_USER_ATTRIBUTE,
                                userId,
                                VetoWebSocketAuthInterceptor.LOGIN_TOKEN_ATTRIBUTE,
                                token));
        return session;
    }
}
