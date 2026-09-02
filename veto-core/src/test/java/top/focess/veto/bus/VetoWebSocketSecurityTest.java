package top.focess.veto.bus;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import top.focess.veto.agent.mcp.ToolDocs;
import top.focess.veto.model.SessionEntity;
import top.focess.veto.model.SessionRepository;
import top.focess.veto.vault.SessionManager;
import top.focess.veto.veto.VetoGateway;

class VetoWebSocketSecurityTest {

    @Test
    void handshakeRequiresAValidSessionToken() {
        SessionManager sessionManager = new SessionManager();
        String token = sessionManager.createSession("alice");
        VetoWebSocketAuthInterceptor interceptor = new VetoWebSocketAuthInterceptor(sessionManager);
        @NonNull ServerHttpRequest validRequest = request("ws://localhost/ws?token=" + token);
        ServerHttpResponse validResponse = mock(ToolDocs.nonNullClass(ServerHttpResponse.class));
        Map<String, Object> attributes = new HashMap<>();

        assertTrue(
                interceptor.beforeHandshake(
                        validRequest,
                        validResponse,
                        mock(ToolDocs.nonNullClass(WebSocketHandler.class)),
                        attributes));
        assertTrue(
                "alice"
                        .equals(
                                attributes.get(
                                        VetoWebSocketAuthInterceptor
                                                .AUTHENTICATED_USER_ATTRIBUTE)));

        ServerHttpResponse rejectedResponse = mock(ToolDocs.nonNullClass(ServerHttpResponse.class));
        assertFalse(
                interceptor.beforeHandshake(
                        request("ws://localhost/ws?token=invalid"),
                        rejectedResponse,
                        mock(ToolDocs.nonNullClass(WebSocketHandler.class)),
                        new HashMap<>()));
        verify(rejectedResponse).setStatusCode(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void deltaFramesReachOnlyConnectionsOwnedByTheSessionUser() throws Exception {
        SessionRepository sessions = mock(ToolDocs.nonNullClass(SessionRepository.class));
        VetoWebSocketHandler handler =
                new VetoWebSocketHandler(
                        new ObjectMapper(),
                        mock(ToolDocs.nonNullClass(VetoGateway.class)),
                        sessions);
        @NonNull WebSocketSession alice = socket("alice-socket", "alice");
        @NonNull WebSocketSession bob = socket("bob-socket", "bob");
        handler.afterConnectionEstablished(alice);
        handler.afterConnectionEstablished(bob);
        clearInvocations(alice, bob);

        SessionEntity session = new SessionEntity("alice", "work");
        when(sessions.findById(session.getId())).thenReturn(Optional.of(session));
        handler.sendFrame(
                DeltaFrame.builder()
                        .sessionId(UUID.fromString(session.getId()))
                        .kind(DeltaFrame.Kind.ASSISTANT_MESSAGE)
                        .text("private")
                        .build());

        verify(alice).sendMessage(org.mockito.ArgumentMatchers.any(TextMessage.class));
        verify(bob, never()).sendMessage(org.mockito.ArgumentMatchers.any(TextMessage.class));
    }

    private static @NonNull ServerHttpRequest request(@NonNull String uri) {
        ServerHttpRequest request = mock(ToolDocs.nonNullClass(ServerHttpRequest.class));
        when(request.getHeaders()).thenReturn(new HttpHeaders());
        when(request.getURI()).thenReturn(URI.create(uri));
        return request;
    }

    private static @NonNull WebSocketSession socket(@NonNull String id, @NonNull String owner) {
        WebSocketSession session = mock(ToolDocs.nonNullClass(WebSocketSession.class));
        when(session.getId()).thenReturn(id);
        when(session.isOpen()).thenReturn(true);
        when(session.getAttributes())
                .thenReturn(
                        Map.of(VetoWebSocketAuthInterceptor.AUTHENTICATED_USER_ATTRIBUTE, owner));
        return session;
    }
}
