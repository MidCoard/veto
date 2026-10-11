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
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
import top.focess.veto.agent.AgentService;
import top.focess.veto.agent.intercept.HitlRegistry;
import top.focess.veto.api.plugin.Scope;
import top.focess.veto.contract.DAGPayload;
import top.focess.veto.contract.Delta;
import top.focess.veto.contract.Frame;
import top.focess.veto.contract.FrameCodec;
import top.focess.veto.model.SessionEntity;
import top.focess.veto.model.SessionRepository;
import top.focess.veto.vault.LoginSessionManager;
import top.focess.veto.vault.TestUsers;

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
                new VetoWebSocketHandler(
                        sessions, tokens, mock(AgentService.class), mock(HitlRegistry.class));
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
        connect(handler, alice);
        connect(handler, bob);
        clearInvocations(alice, bob);

        SessionEntity session = new SessionEntity(TestUsers.ALICE, "work");
        when(sessions.findById(session.getId())).thenReturn(Optional.of(session));
        handler.sendFrame(
                new Delta(
                        new Scope.SessionScope(session.getUserId(), session.getId()),
                        new Delta.Message("private", 0)));

        var sent = ArgumentCaptor.forClass(TextMessage.class);
        verify(alice).sendMessage(sent.capture());
        var frame = FrameCodec.decode(sent.getValue().getPayload());
        if (!(frame instanceof Delta decoded)) throw new AssertionError("Missing event");
        assertEquals(UUID.fromString(session.getId()), decoded.sessionId());
        assertEquals(new Delta.Message("private", 0), decoded.data());
        clearInvocations(alice);
        var broker = new DeltaBroker();
        var bridge = new DeltaBusBridge(broker, handler);
        bridge.start();
        var done =
                new Frame.Done(
                        new Scope.AgentScope(session.getUserId(), session.getId(), "agent"),
                        "request",
                        Map.of("turnNumber", 2),
                        null);
        try {
            broker.publish(done);
            verify(alice).sendMessage(sent.capture());
            assertEquals(done, FrameCodec.decode(sent.getValue().getPayload()));
            clearInvocations(alice);
            var error =
                    new Frame.Error(
                            new Scope.AgentScope(session.getUserId(), session.getId(), "agent"),
                            "failed-request",
                            3,
                            "failed",
                            0);
            broker.publish(error);
            verify(alice).sendMessage(sent.capture());
            assertEquals(error, FrameCodec.decode(sent.getValue().getPayload()));
        } finally {
            bridge.stop();
        }
        verify(bob, never()).sendMessage(ArgumentMatchers.any(TextMessage.class));
    }

    @Test
    void malformedProtocolEnvelopesAreRejected() throws Exception {
        var tokens = new LoginSessionManager();
        var token = tokens.createLoginSession(TestUsers.ALICE, "alice");
        var handler =
                new VetoWebSocketHandler(
                        mock(SessionRepository.class),
                        tokens,
                        mock(AgentService.class),
                        mock(HitlRegistry.class));
        var socket = socket("alice", TestUsers.ALICE, token);
        connect(handler, socket);
        clearInvocations(socket);
        handler.handleTextMessage(
                socket,
                new TextMessage(
                        "{\"type\":\"heartbeat\",\"type\":\"process\",\"payload\":\"private\"}"));
        handler.handleTextMessage(
                socket, new TextMessage("{\"type\":\"process\",\"payload\":\"private\"} {}"));
        var sent = ArgumentCaptor.forClass(TextMessage.class);
        verify(socket, times(2)).sendMessage(sent.capture());
        for (var message : sent.getAllValues()) {
            assertTrue(FrameCodec.decode(message.getPayload()) instanceof Frame.Error);
        }
    }

    @Test
    void sharedFramesRetainWebSocketAdmissionBoundaries() throws Exception {
        var tokens = new LoginSessionManager();
        var handler =
                new VetoWebSocketHandler(
                        mock(SessionRepository.class),
                        tokens,
                        mock(AgentService.class),
                        mock(HitlRegistry.class));
        var socket =
                socket(
                        "alice",
                        TestUsers.ALICE,
                        tokens.createLoginSession(TestUsers.ALICE, "alice"));
        connect(handler, socket);
        handler.handleTextMessage(
                socket, new TextMessage(FrameCodec.encodeString(new Frame.Heartbeat())));
        handler.handleTextMessage(
                socket, new TextMessage(FrameCodec.encodeString(new Frame.Request("/signup"))));
        var sent = ArgumentCaptor.forClass(TextMessage.class);
        verify(socket, times(3)).sendMessage(sent.capture());
        var values = sent.getAllValues();
        var first = FrameCodec.decode(values.get(0).getPayload());
        if (!(first instanceof Frame.Welcome welcome)) throw new AssertionError("Missing welcome");
        assertEquals(Frame.PROTOCOL_VERSION, welcome.version());
        var second = FrameCodec.decode(values.get(1).getPayload());
        assertTrue(second instanceof Frame.HeartbeatAck);
        assertFalse(values.get(1).getPayload().contains("seq"));
        assertTrue(FrameCodec.decode(values.get(2).getPayload()) instanceof Frame.Error);
    }

    @Test
    void helloIsRequiredBeforeDeliveryAndWelcomeEchoesClientSequence() throws Exception {
        var tokens = new LoginSessionManager();
        var sessions = mock(SessionRepository.class);
        var handler =
                new VetoWebSocketHandler(
                        sessions, tokens, mock(AgentService.class), mock(HitlRegistry.class));
        var socket =
                socket(
                        "client",
                        TestUsers.ALICE,
                        tokens.createLoginSession(TestUsers.ALICE, "alice"));
        handler.afterConnectionEstablished(socket);
        handler.sendFrame(event(sessions));
        verify(socket, never()).sendMessage(ArgumentMatchers.any(TextMessage.class));
        hello(handler, socket, 23);
        var sent = ArgumentCaptor.forClass(TextMessage.class);
        verify(socket).sendMessage(sent.capture());
        if (!(FrameCodec.decode(sent.getValue().getPayload()) instanceof Frame.Welcome welcome))
            throw new AssertionError("Missing welcome");
        assertEquals(23, welcome.seq());
        clearInvocations(socket);
        handler.sendFrame(event(sessions));
        verify(socket).sendMessage(ArgumentMatchers.any(TextMessage.class));
    }

    @Test
    void applicationFrameBeforeHelloIsRejected() throws Exception {
        var tokens = new LoginSessionManager();
        var handler =
                new VetoWebSocketHandler(
                        mock(SessionRepository.class),
                        tokens,
                        mock(AgentService.class),
                        mock(HitlRegistry.class));
        var socket =
                socket(
                        "client",
                        TestUsers.ALICE,
                        tokens.createLoginSession(TestUsers.ALICE, "alice"));
        handler.afterConnectionEstablished(socket);
        handler.handleTextMessage(
                socket, new TextMessage(FrameCodec.encodeString(new Frame.Heartbeat())));
        verify(socket).close(CloseStatus.PROTOCOL_ERROR);
    }

    @Test
    void serverOnlyFramesAndUnsupportedVersionsAreRejected() throws Exception {
        var tokens = new LoginSessionManager();
        var handler =
                new VetoWebSocketHandler(
                        mock(SessionRepository.class),
                        tokens,
                        mock(AgentService.class),
                        mock(HitlRegistry.class));
        var socket =
                socket(
                        "client",
                        TestUsers.ALICE,
                        tokens.createLoginSession(TestUsers.ALICE, "alice"));
        connect(handler, socket);
        clearInvocations(socket);
        handler.handleTextMessage(
                socket,
                new TextMessage(
                        FrameCodec.encodeString(
                                new Frame.Welcome(
                                        Frame.PROTOCOL_VERSION, 0, VetoVersion.VERSION))));
        handler.handleTextMessage(
                socket,
                new TextMessage(
                        FrameCodec.encodeString(
                                new Frame.Hello(
                                        Frame.PROTOCOL_VERSION + 1, 24, VetoVersion.VERSION, ""))));
        var sent = ArgumentCaptor.forClass(TextMessage.class);
        verify(socket, times(2)).sendMessage(sent.capture());
        for (var reply : sent.getAllValues())
            assertTrue(FrameCodec.decode(reply.getPayload()) instanceof Frame.Error);
    }

    @Test
    void dagBroadcastHonorsOwnerWithoutEchoingSender() throws Exception {
        var tokens = new LoginSessionManager();
        var handler =
                new VetoWebSocketHandler(
                        mock(SessionRepository.class),
                        tokens,
                        mock(AgentService.class),
                        mock(HitlRegistry.class));
        var token = tokens.createLoginSession(TestUsers.ALICE, "alice");
        var sender = socket("sender", TestUsers.ALICE, token);
        var recipient = socket("recipient", TestUsers.ALICE, token);
        var filtered = socket("filtered", TestUsers.ALICE, token);
        var foreign =
                socket("foreign", TestUsers.BOB, tokens.createLoginSession(TestUsers.BOB, "bob"));
        for (var socket : List.of(sender, recipient, filtered, foreign)) connect(handler, socket);
        clearInvocations(sender, recipient, filtered, foreign);
        handler.handleTextMessage(
                sender,
                new TextMessage(
                        FrameCodec.encodeString(
                                new Frame.DagPayload(
                                        DAGPayload.builder().taskType("test").build(),
                                        "untrusted-source"))));
        var acknowledgement = ArgumentCaptor.forClass(TextMessage.class);
        verify(sender).sendMessage(acknowledgement.capture());
        assertTrue(
                FrameCodec.decode(acknowledgement.getValue().getPayload())
                        instanceof Frame.Received);
        var delivered = ArgumentCaptor.forClass(TextMessage.class);
        verify(recipient).sendMessage(delivered.capture());
        if (!(FrameCodec.decode(delivered.getValue().getPayload()) instanceof Frame.DagPayload dag))
            throw new AssertionError("Missing DAG payload");
        assertEquals("sender", dag.source());
        verify(filtered).sendMessage(ArgumentMatchers.any(TextMessage.class));
        verify(foreign, never()).sendMessage(ArgumentMatchers.any(TextMessage.class));
    }

    @Test
    void revokedTokenCannotReceiveEventsForItsOwnUser() throws Exception {
        var tokens = new LoginSessionManager();
        var token = tokens.createLoginSession(TestUsers.ALICE, "alice");
        var sessions = mock(SessionRepository.class);
        var handler =
                new VetoWebSocketHandler(
                        sessions, tokens, mock(AgentService.class), mock(HitlRegistry.class));
        var socket = socket("revoked", TestUsers.ALICE, token);
        var event = event(sessions);
        connect(handler, socket);
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
        var handler =
                new VetoWebSocketHandler(
                        sessions, tokens, mock(AgentService.class), mock(HitlRegistry.class));
        var failed = socket("failed", TestUsers.ALICE, token);
        var closed = socket("closed", TestUsers.ALICE, token);
        var healthy = socket("healthy", TestUsers.ALICE, token);
        var event = event(sessions);
        for (var socket : List.of(failed, closed, healthy)) connect(handler, socket);
        clearInvocations(failed, closed, healthy);
        doThrow(new IOException("send failed"))
                .when(failed)
                .sendMessage(ArgumentMatchers.any(TextMessage.class));
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
        var handler =
                new VetoWebSocketHandler(
                        mock(SessionRepository.class),
                        tokens,
                        mock(AgentService.class),
                        mock(HitlRegistry.class));
        var failed = socket("failed", TestUsers.ALICE, token);
        doThrow(new IOException("greeting failed"))
                .when(failed)
                .sendMessage(ArgumentMatchers.any(TextMessage.class));
        connect(handler, failed);
        assertEquals(0, handler.getActiveSessionCount());
        verify(failed).close(CloseStatus.SERVER_ERROR);
        var closed = socket("bye", TestUsers.ALICE, token);
        connect(handler, closed);
        handler.handleTextMessage(
                closed, new TextMessage(FrameCodec.encodeString(new Frame.Bye())));
        assertEquals(0, handler.getActiveSessionCount());
        handler.handleTextMessage(
                closed, new TextMessage(FrameCodec.encodeString(new Frame.Heartbeat())));
        verify(closed).close(CloseStatus.NORMAL);
    }

    @Test
    void approvalDecisionChecksOwnerAndAgentMembershipBeforeResolving() throws Exception {
        var tokens = new LoginSessionManager();
        var sessions = mock(SessionRepository.class);
        var agents = mock(AgentService.class);
        var approvals = mock(HitlRegistry.class);
        var handler = new VetoWebSocketHandler(sessions, tokens, agents, approvals);
        var socket =
                socket(
                        "alice",
                        TestUsers.ALICE,
                        tokens.createLoginSession(TestUsers.ALICE, "alice"));
        connect(handler, socket);
        var owned = new SessionEntity(TestUsers.ALICE, "owned");
        owned.setPrimaryAgentId("root");
        var foreign = new SessionEntity(TestUsers.BOB, "foreign");
        foreign.setPrimaryAgentId("foreign-root");
        when(sessions.findById(owned.getId())).thenReturn(Optional.of(owned));
        when(sessions.findById(foreign.getId())).thenReturn(Optional.of(foreign));
        when(approvals.sessionAgents("root")).thenReturn(List.of("root", "member"));
        when(agents.resolveVeto("member", "call", "EXEC_DECLINE", 2)).thenReturn(true);
        clearInvocations(socket);
        var accepted =
                new Frame.VetoResult(
                        new Scope.AgentScope(owned.getUserId(), owned.getId(), "member"),
                        "call",
                        "EXEC_DECLINE",
                        2);
        handler.handleTextMessage(socket, new TextMessage(FrameCodec.encodeString(accepted)));
        handler.handleTextMessage(
                socket,
                new TextMessage(
                        FrameCodec.encodeString(
                                new Frame.VetoResult(
                                        new Scope.AgentScope(
                                                owned.getUserId(), foreign.getId(), "member"),
                                        "call",
                                        "EXEC_DECLINE",
                                        3))));
        handler.handleTextMessage(
                socket,
                new TextMessage(
                        FrameCodec.encodeString(
                                new Frame.VetoResult(
                                        new Scope.AgentScope(
                                                owned.getUserId(), owned.getId(), "foreign-agent"),
                                        "call",
                                        "EXEC_DECLINE",
                                        4))));
        handler.handleTextMessage(
                socket,
                new TextMessage(
                        FrameCodec.encodeString(
                                new Frame.VetoResult(
                                        new Scope.AgentScope(
                                                owned.getUserId(), owned.getId(), "member"),
                                        "expired",
                                        "EXEC_DECLINE",
                                        5))));
        handler.handleTextMessage(
                socket,
                new TextMessage(
                        FrameCodec.encodeString(
                                new Frame.VetoResult(
                                        new Scope.AgentScope(
                                                TestUsers.BOB, owned.getId(), "member"),
                                        "call",
                                        "EXEC_DECLINE",
                                        6))));
        verify(agents).resolveVeto("member", "call", "EXEC_DECLINE", 2);
        verify(agents, never())
                .resolveVeto(
                        ArgumentMatchers.eq("foreign-agent"),
                        ArgumentMatchers.anyString(),
                        ArgumentMatchers.anyString(),
                        ArgumentMatchers.anyLong());
        var sent = ArgumentCaptor.forClass(TextMessage.class);
        verify(socket, times(4)).sendMessage(sent.capture());
        for (int index = 0; index < 4; index++) {
            var reply = FrameCodec.decode(sent.getAllValues().get(index).getPayload());
            if (!(reply instanceof Frame.Error error))
                throw new AssertionError("Expected rejection");
            assertEquals(0, error.seq());
        }
    }

    @Test
    void approvalLifecycleUsesDedicatedWireFrames() throws Exception {
        var tokens = new LoginSessionManager();
        var sessions = mock(SessionRepository.class);
        var handler =
                new VetoWebSocketHandler(
                        sessions, tokens, mock(AgentService.class), mock(HitlRegistry.class));
        var socket =
                socket(
                        "alice",
                        TestUsers.ALICE,
                        tokens.createLoginSession(TestUsers.ALICE, "alice"));
        connect(handler, socket);
        var session = new SessionEntity(TestUsers.ALICE, "owned");
        when(sessions.findById(session.getId())).thenReturn(Optional.of(session));
        clearInvocations(socket);
        handler.sendFrame(
                new Frame.Veto(
                        new Scope.AgentScope(session.getUserId(), session.getId(), "agent"),
                        "call",
                        "run_command",
                        "EXEC_NETWORK",
                        List.of("EXEC_DECLINE"),
                        Map.of(),
                        "DANGEROUS",
                        42));
        handler.sendFrame(
                new Delta(
                        new Scope.AgentScope(session.getUserId(), session.getId(), "agent"),
                        new Delta.ToolResult("call", "Declined by user", false, 1)));
        var sent = ArgumentCaptor.forClass(TextMessage.class);
        verify(socket, times(2)).sendMessage(sent.capture());
        var first = FrameCodec.decode(sent.getAllValues().getFirst().getPayload());
        if (!(first instanceof Frame.Veto veto)) throw new AssertionError("Expected approval");
        assertEquals("DANGEROUS", veto.danger());
        assertEquals("call", veto.callId());
        var second = FrameCodec.decode(sent.getAllValues().get(1).getPayload());
        if (!(second instanceof Delta delta) || !(delta.data() instanceof Delta.ToolResult result))
            throw new AssertionError("Expected tool result");
        assertFalse(result.success());
        assertEquals("call", result.callId());
    }

    private static void connect(
            @NonNull VetoWebSocketHandler handler, @NonNull WebSocketSession socket)
            throws Exception {
        handler.afterConnectionEstablished(socket);
        hello(handler, socket, 1);
    }

    private static void hello(
            @NonNull VetoWebSocketHandler handler, @NonNull WebSocketSession socket, long seq) {
        handler.handleTextMessage(
                socket,
                new TextMessage(
                        FrameCodec.encodeString(
                                new Frame.Hello(
                                        Frame.PROTOCOL_VERSION, seq, VetoVersion.VERSION, ""))));
    }

    private static @NonNull Delta event(@NonNull SessionRepository sessions) {
        var session = new SessionEntity(TestUsers.ALICE, "work");
        when(sessions.findById(session.getId())).thenReturn(Optional.of(session));
        return new Delta(
                new Scope.SessionScope(session.getUserId(), session.getId()),
                new Delta.Message("private", 0));
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
        var handler =
                new VetoWebSocketHandler(
                        sessions, tokens, mock(AgentService.class), mock(HitlRegistry.class));
        var oldSocket = socket("old-alice", TestUsers.ALICE, token);
        connect(handler, oldSocket);
        clearInvocations(oldSocket);
        tokens.revokeToken(token);
        var newToken = tokens.createLoginSession(TestUsers.OWNER, "alice");
        var newSocket = socket("new-alice", TestUsers.OWNER, newToken);
        connect(handler, newSocket);
        clearInvocations(newSocket);
        var session = new SessionEntity(TestUsers.OWNER, "new-account-session");
        when(sessions.findById(session.getId())).thenReturn(Optional.of(session));
        handler.sendFrame(
                new Delta(
                        new Scope.SessionScope(session.getUserId(), session.getId()),
                        new Delta.Message("private-new-account-data", 0)));
        verify(oldSocket, never()).sendMessage(ArgumentMatchers.any(TextMessage.class));
        verify(newSocket).sendMessage(ArgumentMatchers.any(TextMessage.class));
        handler.handleTextMessage(
                oldSocket, new TextMessage("{\"type\":\"process\",\"payload\":\"private\"}"));
        verify(oldSocket).close(ArgumentMatchers.any(CloseStatus.class));
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
