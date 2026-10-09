package top.focess.veto.bus;

import java.util.List;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Origin policy for the authenticated browser WebSocket endpoint. */
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

    /** Allowed origins for /ws/veto/bus on the application's HTTP port. */
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
