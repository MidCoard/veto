package top.focess.veto.bus;

import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;
import top.focess.veto.vault.LoginSessionManager;
import top.focess.veto.vault.LoginSessionManager.LoginSession;

/** Authenticates the WebSocket handshake and binds its Veto user to the connection. */
@Component
public final class VetoWebSocketAuthInterceptor implements HandshakeInterceptor {

    public static final @NonNull String AUTHENTICATED_USER_ATTRIBUTE = "veto.authenticatedUser";
    public static final @NonNull String LOGIN_TOKEN_ATTRIBUTE = "veto.sessionToken";
    private static final @NonNull String TOKEN_HEADER = "X-Veto-Session-Token";

    private final @NonNull LoginSessionManager loginSessions;

    /** Creates the interceptor validating handshake tokens against the login-session manager. */
    public VetoWebSocketAuthInterceptor(@NonNull LoginSessionManager loginSessions) {
        this.loginSessions = loginSessions;
    }

    @Override
    public boolean beforeHandshake(
            @NonNull ServerHttpRequest request,
            @NonNull ServerHttpResponse response,
            @NonNull WebSocketHandler wsHandler,
            @NonNull Map<String, Object> attributes) {
        String token = request.getHeaders().getFirst(TOKEN_HEADER);
        if (token == null || token.isBlank()) {
            token =
                    UriComponentsBuilder.fromUri(request.getURI())
                            .build()
                            .getQueryParams()
                            .getFirst("token");
        }
        if (token == null || token.isBlank()) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        LoginSession authenticated = loginSessions.validateToken(token).orElse(null);
        if (authenticated == null) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        attributes.put(AUTHENTICATED_USER_ATTRIBUTE, authenticated.userId());
        attributes.put(LOGIN_TOKEN_ATTRIBUTE, token);
        return true;
    }

    @Override
    public void afterHandshake(
            @NonNull ServerHttpRequest request,
            @NonNull ServerHttpResponse response,
            @NonNull WebSocketHandler wsHandler,
            Exception exception) {}
}
