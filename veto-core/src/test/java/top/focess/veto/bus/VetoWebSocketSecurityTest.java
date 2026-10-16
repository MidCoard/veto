package top.focess.veto.bus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import jakarta.websocket.Session;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import org.springframework.web.socket.adapter.standard.StandardWebSocketSession;
import top.focess.veto.VetoVersion;
import top.focess.veto.agent.AgentService;
import top.focess.veto.agent.intercept.HitlRegistry;
import top.focess.veto.api.agent.screening.Danger;
import top.focess.veto.api.plugin.Scope;
import top.focess.veto.command.SessionCommandService;
import top.focess.veto.contract.Delta;
import top.focess.veto.contract.Frame;
import top.focess.veto.contract.FrameCodec;
import top.focess.veto.model.SessionEntity;
import top.focess.veto.model.SessionRepository;
import top.focess.veto.session.AgentSessions;
import top.focess.veto.session.SessionService;
import top.focess.veto.session.TestAgentSessions;
import top.focess.veto.vault.LoginSessionManager;
import top.focess.veto.vault.TestUsers;

class VetoWebSocketSecurityTest {
    private final @NonNull Map<@NonNull WebSocketServer, @NonNull AgentSessions> runtimes =
            new HashMap<>();

    private final @NonNull List<@NonNull WebSocketServer> handlers = new ArrayList<>();

    private @NonNull WebSocketServer handler(
            @NonNull SessionRepository sessions,
            @NonNull LoginSessionManager tokens,
            @NonNull AgentService agents,
            @NonNull HitlRegistry approvals) {
        when(agents.hasApprovalAgent(anyString(), anyString()))
                .thenAnswer(
                        invocation -> {
                            String session = invocation.getArgument(0);
                            String agent = invocation.getArgument(1);
                            return session != null
                                    && agent != null
                                    && approvals.belongsToSession(agent, UUID.fromString(session));
                        });
        var runtime = TestAgentSessions.create(sessions, agents);
        var handler =
                new WebSocketServer(
                        mock(SessionService.class),
                        mock(SessionCommandService.class),
                        tokens,
                        runtime);
        runtimes.put(handler, runtime);
        handlers.add(handler);
        return handler;
    }

    private @NonNull AgentSessions runtime(@NonNull WebSocketServer handler) {
        var runtime = runtimes.get(handler);
        if (runtime == null) throw new AssertionError("Missing runtime");
        return runtime;
    }

    private void publish(@NonNull WebSocketServer handler, Frame.@NonNull ScopedFrame frame) {
        var user = frame.scope().userId();
        var session = frame.scope().session();
        if (user == null || session == null) throw new AssertionError("Missing frame identity");
        runtime(handler).session(new Scope.SessionScope(user, session)).send(frame);
    }

    @AfterEach
    void stopWriters() {
        handlers.forEach(WebSocketServer::stop);
    }

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
        WebSocketServer handler =
                handler(sessions, tokens, mock(AgentService.class), mock(HitlRegistry.class));
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
        publish(
                handler,
                new Delta(
                        new Scope.SessionScope(session.getUserId(), session.getId()),
                        new Delta.Message("private", 0)));

        var sent = ArgumentCaptor.forClass(TextMessage.class);
        verify(alice, timeout(2_000)).sendMessage(sent.capture());
        var frame = FrameCodec.decode(sent.getValue().getPayload());
        if (!(frame instanceof Delta decoded)) throw new AssertionError("Missing event");
        assertEquals(UUID.fromString(session.getId()), decoded.sessionId());
        assertEquals(new Delta.Message("private", 0), decoded.data());
        clearInvocations(alice);
        var publication =
                new Frame.PluginAction(
                        new Scope.SessionScope(session.getUserId(), session.getId()),
                        "task.updated",
                        JsonNodeFactory.instance.objectNode().put("ready", true));
        publish(handler, publication);
        verify(alice, timeout(2_000)).sendMessage(sent.capture());
        assertEquals(publication, FrameCodec.decode(sent.getValue().getPayload()));
        verify(bob, never()).sendMessage(ArgumentMatchers.any(TextMessage.class));
        clearInvocations(alice);
        var done =
                new Frame.Done(
                        new Scope.AgentScope(session.getUserId(), session.getId(), "agent"),
                        "request",
                        2,
                        null,
                        null,
                        0);
        publish(handler, done);
        verify(alice, timeout(2_000)).sendMessage(sent.capture());
        assertEquals(done, FrameCodec.decode(sent.getValue().getPayload()));
        clearInvocations(alice);
        var error =
                new Frame.Error(
                        new Scope.AgentScope(session.getUserId(), session.getId(), "agent"),
                        "failed-request",
                        3,
                        "failed",
                        0,
                        null,
                        null);
        publish(handler, error);
        verify(alice, timeout(2_000)).sendMessage(sent.capture());
        assertEquals(error, FrameCodec.decode(sent.getValue().getPayload()));
        verify(bob, never()).sendMessage(ArgumentMatchers.any(TextMessage.class));
    }

    @Test
    void malformedProtocolEnvelopesAreRejected() throws Exception {
        var tokens = new LoginSessionManager();
        var token = tokens.createLoginSession(TestUsers.ALICE, "alice");
        var handler =
                handler(
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
        verify(socket, timeout(2_000).times(2)).sendMessage(sent.capture());
        for (var message : sent.getAllValues()) {
            assertTrue(FrameCodec.decode(message.getPayload()) instanceof Frame.Error);
        }
    }

    @Test
    void sharedFramesRetainWebSocketAdmissionBoundaries() throws Exception {
        var tokens = new LoginSessionManager();
        var handler =
                handler(
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
                socket,
                new TextMessage(
                        FrameCodec.encodeString(new Frame.Request(2, null, null, "/signup"))));
        handler.handleTextMessage(
                socket, new TextMessage(FrameCodec.encodeString(new Frame.Cancel(9, 999))));
        var sent = ArgumentCaptor.forClass(TextMessage.class);
        verify(socket, timeout(2_000).times(4)).sendMessage(sent.capture());
        var values = sent.getAllValues();
        var first = FrameCodec.decode(values.get(0).getPayload());
        if (!(first instanceof Frame.Welcome welcome)) throw new AssertionError("Missing welcome");
        assertEquals(Frame.PROTOCOL_VERSION, welcome.version());
        var second = FrameCodec.decode(values.get(1).getPayload());
        assertTrue(second instanceof Frame.HeartbeatAck);
        assertFalse(values.get(1).getPayload().contains("seq"));
        var replies =
                values.subList(2, 4).stream()
                        .map(message -> FrameCodec.decode(message.getPayload()))
                        .toList();
        assertTrue(
                replies.stream()
                        .anyMatch(frame -> frame instanceof Frame.Error error && error.seq() == 2));
        assertTrue(
                replies.stream()
                        .anyMatch(
                                frame ->
                                        frame instanceof Frame.CancelResult result
                                                && result.seq() == 9
                                                && result.status()
                                                        == Frame.CancelStatus.NO_ACTIVE_REQUEST));
    }

    @Test
    void helloIsRequiredBeforeDeliveryAndWelcomeEchoesClientSequence() throws Exception {
        var tokens = new LoginSessionManager();
        var sessions = mock(SessionRepository.class);
        var handler = handler(sessions, tokens, mock(AgentService.class), mock(HitlRegistry.class));
        var socket =
                socket(
                        "client",
                        TestUsers.ALICE,
                        tokens.createLoginSession(TestUsers.ALICE, "alice"));
        handler.afterConnectionEstablished(socket);
        publish(handler, event(sessions));
        verify(socket, never()).sendMessage(ArgumentMatchers.any(TextMessage.class));
        hello(handler, socket, 23);
        var sent = ArgumentCaptor.forClass(TextMessage.class);
        verify(socket, timeout(2_000)).sendMessage(sent.capture());
        if (!(FrameCodec.decode(sent.getValue().getPayload()) instanceof Frame.Welcome welcome))
            throw new AssertionError("Missing welcome");
        assertEquals(23, welcome.seq());
        clearInvocations(socket);
        publish(handler, event(sessions));
        verify(socket, timeout(2_000)).sendMessage(ArgumentMatchers.any(TextMessage.class));
    }

    @Test
    void applicationFrameBeforeHelloIsRejected() throws Exception {
        var tokens = new LoginSessionManager();
        var handler =
                handler(
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
        verify(socket, timeout(2_000)).close(CloseStatus.PROTOCOL_ERROR);
    }

    @Test
    void serverOnlyFramesAndUnsupportedVersionsAreRejected() throws Exception {
        var tokens = new LoginSessionManager();
        var handler =
                handler(
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
                                        Frame.PROTOCOL_VERSION, 1, VetoVersion.VERSION))));
        handler.handleTextMessage(
                socket,
                new TextMessage(
                        FrameCodec.encodeString(
                                new Frame.Hello(
                                        Frame.PROTOCOL_VERSION + 1, 24, VetoVersion.VERSION, ""))));
        var sent = ArgumentCaptor.forClass(TextMessage.class);
        verify(socket, timeout(2_000).times(2)).sendMessage(sent.capture());
        for (var reply : sent.getAllValues())
            assertTrue(FrameCodec.decode(reply.getPayload()) instanceof Frame.Error);
    }

    @Test
    void clientCannotInjectPluginActions() throws Exception {
        var tokens = new LoginSessionManager();
        var handler =
                handler(
                        mock(SessionRepository.class),
                        tokens,
                        mock(AgentService.class),
                        mock(HitlRegistry.class));
        var sender =
                socket(
                        "sender",
                        TestUsers.ALICE,
                        tokens.createLoginSession(TestUsers.ALICE, "alice"));
        connect(handler, sender);
        clearInvocations(sender);
        handler.handleTextMessage(
                sender, new TextMessage(FrameCodec.encodeString(Delta.message("forged"))));
        var reply = ArgumentCaptor.forClass(TextMessage.class);
        verify(sender, timeout(2_000)).sendMessage(reply.capture());
        assertTrue(FrameCodec.decode(reply.getValue().getPayload()) instanceof Frame.Error);
    }

    @Test
    void revokedTokenCannotReceiveEventsForItsOwnUser() throws Exception {
        var tokens = new LoginSessionManager();
        var token = tokens.createLoginSession(TestUsers.ALICE, "alice");
        var sessions = mock(SessionRepository.class);
        var handler = handler(sessions, tokens, mock(AgentService.class), mock(HitlRegistry.class));
        var socket = socket("revoked", TestUsers.ALICE, token);
        var event = event(sessions);
        connect(handler, socket);
        clearInvocations(socket);
        tokens.revokeToken(token);
        publish(handler, event);
        verify(socket, timeout(2_000))
                .close(CloseStatus.POLICY_VIOLATION.withReason("authentication expired"));
        assertEquals(0, handler.getActiveSessionCount());
        verify(socket, never()).sendMessage(ArgumentMatchers.any(TextMessage.class));
    }

    @Test
    void failedAndClosedPeersDoNotRetainConnectionsOrBlockHealthyDelivery() throws Exception {
        var tokens = new LoginSessionManager();
        var token = tokens.createLoginSession(TestUsers.ALICE, "alice");
        var sessions = mock(SessionRepository.class);
        var handler = handler(sessions, tokens, mock(AgentService.class), mock(HitlRegistry.class));
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
        publish(handler, event);
        verify(failed, timeout(2_000)).close(CloseStatus.SERVER_ERROR);
        assertEquals(1, handler.getActiveSessionCount());
        verify(closed, never()).sendMessage(ArgumentMatchers.any(TextMessage.class));
        verify(healthy, timeout(2_000)).sendMessage(ArgumentMatchers.any(TextMessage.class));
        handler.handleTransportError(healthy, new IOException("connection failed"));
        assertEquals(0, handler.getActiveSessionCount());
        handler.afterConnectionClosed(healthy, CloseStatus.SERVER_ERROR);
        verify(healthy, timeout(2_000)).close(CloseStatus.SERVER_ERROR);
    }

    @Test
    void greetingFailureAndByeRemoveAdmissionBeforeLaterMessages() throws Exception {
        var tokens = new LoginSessionManager();
        var token = tokens.createLoginSession(TestUsers.ALICE, "alice");
        var handler =
                handler(
                        mock(SessionRepository.class),
                        tokens,
                        mock(AgentService.class),
                        mock(HitlRegistry.class));
        var failed = socket("failed", TestUsers.ALICE, token);
        doThrow(new IOException("greeting failed"))
                .when(failed)
                .sendMessage(ArgumentMatchers.any(TextMessage.class));
        connect(handler, failed);
        verify(failed, timeout(2_000)).close(CloseStatus.SERVER_ERROR);
        assertEquals(0, handler.getActiveSessionCount());
        var closed = socket("bye", TestUsers.ALICE, token);
        connect(handler, closed);
        handler.handleTextMessage(
                closed, new TextMessage(FrameCodec.encodeString(new Frame.Bye())));
        assertEquals(0, handler.getActiveSessionCount());
        handler.handleTextMessage(
                closed, new TextMessage(FrameCodec.encodeString(new Frame.Heartbeat())));
        verify(closed, timeout(2_000)).close(CloseStatus.NORMAL);
    }

    @Test
    void approvalDecisionChecksOwnerAndAgentMembershipBeforeResolving() throws Exception {
        var tokens = new LoginSessionManager();
        var sessions = mock(SessionRepository.class);
        var agents = mock(AgentService.class);
        var approvals = mock(HitlRegistry.class);
        var handler = handler(sessions, tokens, agents, approvals);
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
        when(approvals.belongsToSession("member", UUID.fromString(owned.getId()))).thenReturn(true);
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
        verify(agents, timeout(2_000)).resolveVeto("member", "call", "EXEC_DECLINE", 2);
        var sent = ArgumentCaptor.forClass(TextMessage.class);
        verify(socket, timeout(2_000).times(4)).sendMessage(sent.capture());
        for (int index = 0; index < 4; index++) {
            var reply = FrameCodec.decode(sent.getAllValues().get(index).getPayload());
            if (!(reply instanceof Frame.Error error))
                throw new AssertionError("Expected rejection");
            assertEquals(0, error.seq());
        }
        verify(agents, never())
                .resolveVeto(
                        ArgumentMatchers.eq("foreign-agent"),
                        ArgumentMatchers.anyString(),
                        ArgumentMatchers.anyString(),
                        ArgumentMatchers.anyLong());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @Timeout(15)
    void logoutDuringApprovalLookupCannotCommitAStaleDecision(boolean revokeAccount)
            throws Exception {
        var tokens = new LoginSessionManager();
        var token = tokens.createLoginSession(TestUsers.ALICE, "alice");
        var sessions = mock(SessionRepository.class);
        var agents = mock(AgentService.class);
        var approvals = mock(HitlRegistry.class);
        var handler = handler(sessions, tokens, agents, approvals);
        var socket = socket("revoked-approval", TestUsers.ALICE, token);
        connect(handler, socket);
        var owned = new SessionEntity(TestUsers.ALICE, "owned");
        owned.setPrimaryAgentId("agent");
        when(approvals.belongsToSession("agent", UUID.fromString(owned.getId()))).thenReturn(true);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(sessions.findById(owned.getId()))
                .thenAnswer(
                        invocation -> {
                            entered.countDown();
                            assertTrue(release.await(5, TimeUnit.SECONDS));
                            return Optional.of(owned);
                        });
        var stale =
                new Frame.VetoResult(
                        new Scope.AgentScope(TestUsers.ALICE, owned.getId(), "agent"),
                        "call",
                        "ACCEPT_GENERIC",
                        7);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var pending =
                    workers.submit(
                            () ->
                                    handler.handleTextMessage(
                                            socket,
                                            new TextMessage(FrameCodec.encodeString(stale))));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                if (revokeAccount) tokens.revokeUserTokens(TestUsers.ALICE);
                else tokens.revokeToken(token);
                assertTrue(tokens.validateToken(token).isEmpty());
            } finally {
                release.countDown();
            }
            pending.get(5, TimeUnit.SECONDS);
        }
        verify(socket, timeout(2_000))
                .close(CloseStatus.POLICY_VIOLATION.withReason("authentication expired"));
        verify(agents, never()).resolveVeto("agent", "call", "ACCEPT_GENERIC", 7);

        var current =
                socket(
                        "current-approval",
                        TestUsers.ALICE,
                        tokens.createLoginSession(TestUsers.ALICE, "alice"));
        connect(handler, current);
        when(agents.resolveVeto("agent", "call", "ACCEPT_GENERIC", 8)).thenReturn(true);
        var valid = new Frame.VetoResult(stale.scope(), "call", "ACCEPT_GENERIC", 8);
        handler.handleTextMessage(current, new TextMessage(FrameCodec.encodeString(valid)));
        verify(agents, timeout(2_000)).resolveVeto("agent", "call", "ACCEPT_GENERIC", 8);
    }

    @Test
    void approvalLifecycleUsesDedicatedWireFrames() throws Exception {
        var tokens = new LoginSessionManager();
        var sessions = mock(SessionRepository.class);
        var handler = handler(sessions, tokens, mock(AgentService.class), mock(HitlRegistry.class));
        var socket =
                socket(
                        "alice",
                        TestUsers.ALICE,
                        tokens.createLoginSession(TestUsers.ALICE, "alice"));
        connect(handler, socket);
        var session = new SessionEntity(TestUsers.ALICE, "owned");
        when(sessions.findById(session.getId())).thenReturn(Optional.of(session));
        clearInvocations(socket);
        publish(
                handler,
                new Frame.Veto(
                        new Scope.AgentScope(session.getUserId(), session.getId(), "agent"),
                        "call",
                        "run_command",
                        "EXEC_NETWORK",
                        List.of("EXEC_DECLINE"),
                        Map.of(),
                        Danger.DANGEROUS,
                        null,
                        42));
        publish(
                handler,
                new Delta(
                        new Scope.AgentScope(session.getUserId(), session.getId(), "agent"),
                        new Delta.ToolResult("call", "Declined by user", false, 1)));
        var sent = ArgumentCaptor.forClass(TextMessage.class);
        verify(socket, timeout(2_000).times(2)).sendMessage(sent.capture());
        var first = FrameCodec.decode(sent.getAllValues().getFirst().getPayload());
        if (!(first instanceof Frame.Veto veto)) throw new AssertionError("Expected approval");
        assertEquals(Danger.DANGEROUS, veto.danger());
        assertEquals("call", veto.callId());
        var second = FrameCodec.decode(sent.getAllValues().get(1).getPayload());
        if (!(second instanceof Delta delta) || !(delta.data() instanceof Delta.ToolResult result))
            throw new AssertionError("Expected tool result");
        assertFalse(result.success());
        assertEquals("call", result.callId());
    }

    private static void connect(@NonNull WebSocketServer handler, @NonNull WebSocketSession socket)
            throws Exception {
        handler.afterConnectionEstablished(socket);
        hello(handler, socket, 1);
        verify(socket, timeout(2_000)).sendMessage(ArgumentMatchers.any(TextMessage.class));
    }

    private static void hello(
            @NonNull WebSocketServer handler, @NonNull WebSocketSession socket, long seq) {
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
        var handler = handler(sessions, tokens, mock(AgentService.class), mock(HitlRegistry.class));
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
        publish(
                handler,
                new Delta(
                        new Scope.SessionScope(session.getUserId(), session.getId()),
                        new Delta.Message("private-new-account-data", 0)));
        verify(oldSocket, never()).sendMessage(ArgumentMatchers.any(TextMessage.class));
        verify(newSocket, timeout(2_000)).sendMessage(ArgumentMatchers.any(TextMessage.class));
        handler.handleTextMessage(
                oldSocket, new TextMessage("{\"type\":\"process\",\"payload\":\"private\"}"));
        verify(oldSocket, timeout(2_000)).close(ArgumentMatchers.any(CloseStatus.class));
    }

    private static @NonNull WebSocketSession socket(
            @NonNull String id, @NonNull UUID userId, @NonNull String token) {
        var session = mock(StandardWebSocketSession.class);
        var nativeSession = mock(Session.class);
        when(nativeSession.getUserProperties()).thenReturn(new HashMap<>());
        when(session.getNativeSession()).thenReturn(nativeSession);
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
