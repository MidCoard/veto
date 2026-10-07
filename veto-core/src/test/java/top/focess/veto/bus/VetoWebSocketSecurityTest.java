package top.focess.veto.bus;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
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
                new VetoWebSocketHandler(
                        new ObjectMapper(), mock(VetoGateway.class), sessions, tokens);
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
                DeltaFrame.builder()
                        .sessionId(UUID.fromString(session.getId()))
                        .kind(DeltaFrame.Kind.ASSISTANT_MESSAGE)
                        .text("private")
                        .build());

        verify(alice).sendMessage(ArgumentMatchers.any(TextMessage.class));
        verify(bob, never()).sendMessage(ArgumentMatchers.any(TextMessage.class));
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
        var handler = new VetoWebSocketHandler(new ObjectMapper(), gateway, sessions, tokens);
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
                DeltaFrame.builder()
                        .sessionId(UUID.fromString(session.getId()))
                        .kind(DeltaFrame.Kind.ASSISTANT_MESSAGE)
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
