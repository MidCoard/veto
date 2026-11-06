package top.focess.veto.bus;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/** Registers browser and explicitly enabled terminal endpoints with the same origin policy. */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.bus.WebSocketConfig");

    private final @NonNull WebSocketServer webSocketServer;
    private final @NonNull VetoWebSocketAuthInterceptor authInterceptor;
    private final @NonNull BusConfiguration busConfiguration;
    private final boolean terminalEnabled;

    /** Creates the configuration with its handler, auth interceptor, and bus settings. */
    public WebSocketConfig(
            @NonNull WebSocketServer webSocketServer,
            @NonNull VetoWebSocketAuthInterceptor authInterceptor,
            @NonNull BusConfiguration busConfiguration,
            @Value("${veto.terminal.enabled:false}") boolean terminalEnabled) {
        this.webSocketServer = webSocketServer;
        this.authInterceptor = authInterceptor;
        this.busConfiguration = busConfiguration;
        this.terminalEnabled = terminalEnabled;
    }

    @Override
    public void registerWebSocketHandlers(@NonNull WebSocketHandlerRegistry registry) {
        var paths =
                terminalEnabled
                        ? new String[] {"/ws/veto/bus", "/ws/veto/terminal"}
                        : new String[] {"/ws/veto/bus"};
        registry.addHandler(webSocketServer, paths)
                .addInterceptors(authInterceptor)
                .setAllowedOriginPatterns(
                        busConfiguration
                                .getWebsocket()
                                .getAllowedOriginPatterns()
                                .toArray(String[]::new));

        log.info("WS Config: Registered endpoints {}", String.join(", ", paths));
    }
}
