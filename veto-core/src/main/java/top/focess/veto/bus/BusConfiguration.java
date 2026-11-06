package top.focess.veto.bus;

import java.util.List;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Shared browser origin policy for REST and WebSocket endpoints. */
@Configuration
@ConfigurationProperties(prefix = "veto.bus")
public class BusConfiguration {

    private @NonNull WebSocketConfig websocket = new WebSocketConfig();

    public @NonNull WebSocketConfig getWebsocket() {
        return websocket;
    }

    public void setWebsocket(@NonNull WebSocketConfig websocket) {
        this.websocket = websocket;
    }

    /** Allowed browser origins for REST and WebSockets on the application's HTTP port. */
    public static class WebSocketConfig {
        private @NonNull List<@NonNull String> allowedOriginPatterns =
                List.of(
                        "http://localhost:*",
                        "https://localhost:*",
                        "http://127.0.0.1:*",
                        "https://127.0.0.1:*",
                        "http://[::1]:*",
                        "https://[::1]:*");

        public @NonNull List<@NonNull String> getAllowedOriginPatterns() {
            return allowedOriginPatterns;
        }

        public void setAllowedOriginPatterns(@NonNull List<@NonNull String> allowedOriginPatterns) {
            this.allowedOriginPatterns = List.copyOf(allowedOriginPatterns);
        }
    }
}
