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
    private final @NonNull VetoWebSocketAuthInterceptor authInterceptor;
    private final @NonNull BusConfiguration busConfiguration;

    public WebSocketConfig(
            @NonNull VetoWebSocketHandler vetoWebSocketHandler,
            @NonNull VetoWebSocketAuthInterceptor authInterceptor,
            @NonNull BusConfiguration busConfiguration) {
        this.vetoWebSocketHandler = vetoWebSocketHandler;
        this.authInterceptor = authInterceptor;
        this.busConfiguration = busConfiguration;
    }

    @Override
    public void registerWebSocketHandlers(@NonNull WebSocketHandlerRegistry registry) {
        registry.addHandler(vetoWebSocketHandler, "/ws/veto/bus")
                .addInterceptors(authInterceptor)
                .setAllowedOriginPatterns(
                        busConfiguration
                                .getWebsocket()
                                .getAllowedOriginPatterns()
                                .toArray(String[]::new))
                .withSockJS()
                .setClientLibraryUrl(
                        "https://cdn.jsdelivr.net/npm/sockjs-client@1/dist/sockjs.min.js");

        log.info("WS Config: Registered /ws/veto/bus handler with SockJS fallback");
    }
}
