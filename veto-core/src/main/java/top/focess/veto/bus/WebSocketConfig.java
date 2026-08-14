package top.focess.veto.bus;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * Spring WebSocket configuration. Registers the Veto WebSocket handler at /ws/veto/bus for client
 * connections. Also configures allowed origins and SockJS fallback.
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.bus.WebSocketConfig");

    private final @NonNull VetoWebSocketHandler vetoWebSocketHandler;

    public WebSocketConfig(@NonNull VetoWebSocketHandler vetoWebSocketHandler) {
        this.vetoWebSocketHandler = vetoWebSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(@NonNull WebSocketHandlerRegistry registry) {
        registry.addHandler(vetoWebSocketHandler, "/ws/veto/bus")
                // SockJS enables session cookies (allowCredentials), so "*" is rejected in
                // allowedOrigins – use origin patterns, which Spring expands per-request.
                .setAllowedOriginPatterns("*")
                .withSockJS()
                .setClientLibraryUrl(
                        "https://cdn.jsdelivr.net/npm/sockjs-client@1/dist/sockjs.min.js");

        log.info("WS Config: Registered /ws/veto/bus handler with SockJS fallback");
    }
}
