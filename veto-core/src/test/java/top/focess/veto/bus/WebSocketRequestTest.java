package top.focess.veto.bus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.websocket.Session;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.http.HttpHeaders;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.adapter.standard.StandardWebSocketSession;
import top.focess.veto.agent.AgentService;
import top.focess.veto.agent.ProtectedInputException;
import top.focess.veto.agent.RequestHandle;
import top.focess.veto.agent.VetoAgent;
import top.focess.veto.api.agent.AgentResult;
import top.focess.veto.api.llm.ProviderType;
import top.focess.veto.api.llm.ToolResultPresentationMode;
import top.focess.veto.api.plugin.Scope;
import top.focess.veto.command.SessionCommandService;
import top.focess.veto.contract.Frame;
import top.focess.veto.contract.FrameCodec;
import top.focess.veto.contract.Version;
import top.focess.veto.model.SessionEntity;
import top.focess.veto.model.SessionRepository;
import top.focess.veto.model.tier.ModelBinding;
import top.focess.veto.session.LlmConfig;
import top.focess.veto.session.SessionService;
import top.focess.veto.session.TestAgentSessions;
import top.focess.veto.vault.LoginSessionManager;
import top.focess.veto.vault.TestUsers;

/** Exercises native WS decoding, the common connection, and its actual output writer. */
@Timeout(10)
class WebSocketRequestTest {
    private final @NonNull SessionService sessions = mock(SessionService.class);
    private final @NonNull SessionCommandService commands = mock(SessionCommandService.class);
    private final @NonNull AgentService agents = mock(AgentService.class);
    private final @NonNull SessionRepository repository = mock(SessionRepository.class);
    private final @NonNull LoginSessionManager tokens = new LoginSessionManager();
    private final @NonNull String token = tokens.createLoginSession(TestUsers.ALICE, "alice");
    private final @NonNull WebSocketServer handler =
            new WebSocketServer(
                    sessions, commands, tokens, TestAgentSessions.create(repository, agents));
    private final @NonNull StandardWebSocketSession socket = mock(StandardWebSocketSession.class);
    private final @NonNull VetoAgent primary = mock(VetoAgent.class);
    private final @NonNull VetoAgent child = mock(VetoAgent.class);
    private final @NonNull SessionEntity entity = new SessionEntity(TestUsers.ALICE, "work");
    private final @NonNull UUID sessionId = UUID.fromString(entity.getId());
    private final @NonNull BlockingQueue<Frame.@NonNull ServerFrame> output =
            new LinkedBlockingQueue<>();

    @BeforeEach
    void connect() throws Exception {
        var nativeSession = mock(Session.class);
        when(nativeSession.getUserProperties()).thenReturn(new HashMap<>());
        when(socket.getNativeSession()).thenReturn(nativeSession);
        when(socket.getId()).thenReturn("request-client");
        when(socket.isOpen()).thenReturn(true);
        when(socket.getAttributes())
                .thenReturn(
                        Map.of(
                                VetoWebSocketAuthInterceptor.AUTHENTICATED_USER_ATTRIBUTE,
                                TestUsers.ALICE,
                                VetoWebSocketAuthInterceptor.LOGIN_TOKEN_ATTRIBUTE,
                                token));
        var headers = new HttpHeaders();
        headers.setAcceptLanguageAsLocales(List.of(Locale.CHINESE));
        when(socket.getHandshakeHeaders()).thenReturn(headers);
        doAnswer(
                        invocation -> {
                            TextMessage message = invocation.getArgument(0);
                            if (message == null) throw new AssertionError("Missing socket output");
                            var frame = FrameCodec.decode(message.getPayload());
                            if (!(frame instanceof Frame.ServerFrame reply))
                                throw new AssertionError("Invalid server frame");
                            assertTrue(output.offer(reply));
                            return null;
                        })
                .when(socket)
                .sendMessage(any(TextMessage.class));
        when(repository.findById(entity.getId())).thenReturn(Optional.of(entity));
        var model =
                new ModelBinding(
                        ProviderType.ANTHROPIC,
                        "fixture-model",
                        "credential",
                        0.2,
                        7000,
                        "https://example.test/api",
                        96000);
        var config =
                new LlmConfig(
                        model.provider(),
                        model.model(),
                        model.credentialKey(),
                        model.baseUrl(),
                        model.llmOptions());
        when(sessions.prepare(entity.getId(), TestUsers.ALICE))
                .thenReturn(
                        Optional.of(
                                new SessionService.SessionConfig(
                                        entity.getId(), config, ToolResultPresentationMode.BASIC)));
        when(agents.agent(entity.getId())).thenReturn(primary);
        when(primary.id()).thenReturn("primary");
        when(agents.sessionAgent(entity.getId(), "child")).thenReturn(child);
        when(child.id()).thenReturn("child");
        when(child.userInteractionEnabled()).thenReturn(true);
        handler.afterConnectionEstablished(socket);
        send(new Frame.Hello(Frame.PROTOCOL_VERSION, 1, Version.UNKNOWN, ""));
        assertInstanceOf(Frame.Welcome.class, receive());
    }

    @AfterEach
    void close() throws InterruptedException {
        handler.stop();
    }

    @Test
    void primaryPromptTraversesWebSocketAndCompletesOnlyAfterSettlement() throws Exception {
        var request = request("episode");
        String raw = "  Explain TCP\nwithout trimming  ";
        when(primary.submitRequest(eq(raw), any(), eq(Locale.CHINESE), isNull(), any()))
                .thenAnswer(invocation -> admit(invocation, request));
        send(new Frame.Request(2, sessionId, null, raw));
        var accepted = assertInstanceOf(Frame.RequestAccepted.class, receive());
        assertEquals(2, accepted.seq());
        assertEquals(
                new Scope.AgentScope(TestUsers.ALICE, entity.getId(), "primary"), accepted.scope());
        assertEquals("episode", accepted.requestId());
        assertTrue(request.result().complete(AgentResult.success("done", Map.of("turns", 3))));
        send(new Frame.Heartbeat());
        assertInstanceOf(Frame.HeartbeatAck.class, receive());
        assertNull(output.poll(50, TimeUnit.MILLISECONDS));
        assertTrue(request.settled().complete(true));
        var done = assertInstanceOf(Frame.Done.class, receive());
        assertEquals(2, done.seq());
        assertEquals(3, done.turnNumber());
    }

    @Test
    void websocketCancellationTargetsOneRequestAndAcknowledgesBeforeInlineSettlement()
            throws Exception {
        var first = request("first");
        var second = request("second");
        when(primary.submitRequest(eq("first"), any(), any(), isNull(), any()))
                .thenAnswer(invocation -> admit(invocation, first));
        when(primary.submitRequest(eq("second"), any(), any(), isNull(), any()))
                .thenAnswer(invocation -> admit(invocation, second));
        send(new Frame.Request(2, sessionId, null, "first"));
        assertEquals(2, assertInstanceOf(Frame.RequestAccepted.class, receive()).seq());
        send(new Frame.Request(3, sessionId, null, "second"));
        assertEquals(3, assertInstanceOf(Frame.RequestAccepted.class, receive()).seq());
        when(first.requestCancellation())
                .thenAnswer(
                        ignored -> {
                            assertTrue(
                                    first.result()
                                            .complete(AgentResult.success("cancelled", Map.of())));
                            assertTrue(first.settled().complete(true));
                            return true;
                        });

        send(new Frame.Cancel(4, 2));
        var cancelled = assertInstanceOf(Frame.CancelResult.class, receive());
        assertEquals(4, cancelled.seq());
        assertEquals(Frame.CancelStatus.ACCEPTED, cancelled.status());
        assertEquals(2, assertInstanceOf(Frame.Done.class, receive()).seq());
        verify(second, never()).requestCancellation();
        send(new Frame.Heartbeat());
        assertInstanceOf(Frame.HeartbeatAck.class, receive());
    }

    @Test
    void childPromptAndCompactionUseTheSameConnectionWithActualAcceptedAgent() throws Exception {
        var direct = request("child");
        when(child.submitUserPrompt(eq("raw"), eq(Locale.CHINESE), any()))
                .thenAnswer(invocation -> admit(invocation, direct));
        send(new Frame.Request(2, sessionId, "child", "raw"));
        assertEquals(
                "child", assertInstanceOf(Frame.RequestAccepted.class, receive()).scope().agent());
        var compact = request("compact");
        when(commands.supports("/compact")).thenReturn(true);
        when(primary.compact(eq(Locale.CHINESE), any()))
                .thenAnswer(invocation -> admit(invocation, compact));
        send(new Frame.Request(3, sessionId, "child", "/compact"));
        assertEquals(
                "primary",
                assertInstanceOf(Frame.RequestAccepted.class, receive()).scope().agent());
        verify(child, never()).submitUserPrompt(eq("/compact"), any(), any());
        verify(commands, timeout(2_000))
                .primaryNotice(TestUsers.ALICE, entity.getId(), "child", Locale.CHINESE);
    }

    @Test
    void protectedInputRejectionAndBulkRefusalRetainWireCorrelation() throws Exception {
        when(primary.submitRequest(anyString(), any(), any(), isNull(), any()))
                .thenThrow(new ProtectedInputException());
        send(new Frame.Request(2, sessionId, null, "private draft"));
        var rejected = assertInstanceOf(Frame.Error.class, receive());
        assertEquals(2, rejected.seq());
        assertEquals("PROTECTED_INPUT_UNAVAILABLE", rejected.code());
        send(new Frame.DeclineVetoes(3, sessionId));
        var declined = assertInstanceOf(Frame.Done.class, receive());
        assertEquals(3, declined.seq());
        assertEquals(entity.getId(), declined.scope().session());
    }

    @Test
    void expiredTokenClosesWithPolicyViolationBeforeRequestPreparation() throws Exception {
        tokens.revokeToken(token);
        send(new Frame.Request(2, sessionId, null, "must not submit"));
        verify(socket, timeout(2_000))
                .close(CloseStatus.POLICY_VIOLATION.withReason("authentication expired"));
        verify(sessions, never()).prepare(anyString(), any());
        verify(primary, never()).submitRequest(anyString(), any(), any(), isNull(), any());
    }

    private void send(Frame.@NonNull ClientFrame frame) {
        handler.handleTextMessage(socket, new TextMessage(FrameCodec.encodeString(frame)));
    }

    private Frame.@NonNull ServerFrame receive() throws InterruptedException {
        var frame = output.poll(3, TimeUnit.SECONDS);
        if (frame == null) throw new AssertionError("Missing WebSocket reply");
        return frame;
    }

    private static @NonNull RequestHandle admit(
            @NonNull InvocationOnMock invocation, @NonNull RequestHandle handle) {
        RequestHandle.Admission admission =
                invocation.getArgument(invocation.getArguments().length - 1);
        if (admission == null) throw new AssertionError("Missing admission guard");
        return admission.enqueue(() -> handle);
    }

    private static @NonNull RequestHandle request(@NonNull String episode) {
        var handle = mock(RequestHandle.class);
        when(handle.requestId()).thenReturn(episode);
        when(handle.result()).thenReturn(new CompletableFuture<>());
        when(handle.settled()).thenReturn(new CompletableFuture<>());
        return handle;
    }
}
