package top.focess.veto.bus;

import java.util.List;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Configuration for bus Communication & Routing Bus. */
@Configuration
@ConfigurationProperties(prefix = "veto.bus")
public class BusConfiguration {

    private @NonNull WebSocketConfig websocket = new WebSocketConfig();
    private @NonNull GrpcConfig grpc = new GrpcConfig();

    public @NonNull WebSocketConfig getWebsocket() {
        return websocket;
    }

    public void setWebsocket(@NonNull WebSocketConfig websocket) {
        this.websocket = websocket;
    }

    public @NonNull GrpcConfig getGrpc() {
        return grpc;
    }

    public void setGrpc(@NonNull GrpcConfig grpc) {
        this.grpc = grpc;
    }

    public static class WebSocketConfig {
        private int port = 9090;
        private @NonNull String path = "/veto/bus";
        private int heartbeatIntervalMs = 30000;
        private int reconnectDelayMs = 5000;
        private int maxReconnectAttempts = 10;
        private @NonNull List<@NonNull String> allowedOriginPatterns =
                List.of(
                        "http://localhost:*",
                        "https://localhost:*",
                        "http://127.0.0.1:*",
                        "https://127.0.0.1:*",
                        "http://[::1]:*",
                        "https://[::1]:*");

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public @NonNull String getPath() {
            return path;
        }

        public void setPath(@NonNull String path) {
            this.path = path;
        }

        public int getHeartbeatIntervalMs() {
            return heartbeatIntervalMs;
        }

        public void setHeartbeatIntervalMs(int heartbeatIntervalMs) {
            this.heartbeatIntervalMs = heartbeatIntervalMs;
        }

        public int getReconnectDelayMs() {
            return reconnectDelayMs;
        }

        public void setReconnectDelayMs(int reconnectDelayMs) {
            this.reconnectDelayMs = reconnectDelayMs;
        }

        public int getMaxReconnectAttempts() {
            return maxReconnectAttempts;
        }

        public void setMaxReconnectAttempts(int maxReconnectAttempts) {
            this.maxReconnectAttempts = maxReconnectAttempts;
        }

        public @NonNull List<@NonNull String> getAllowedOriginPatterns() {
            return allowedOriginPatterns;
        }

        public void setAllowedOriginPatterns(@NonNull List<@NonNull String> allowedOriginPatterns) {
            this.allowedOriginPatterns = List.copyOf(allowedOriginPatterns);
        }
    }

    public static class GrpcConfig {
        private int port = 9091;

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }
    }
}
