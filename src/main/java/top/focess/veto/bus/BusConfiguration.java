package top.focess.veto.bus;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for C3 Communication & Routing Bus.
 */
@Configuration
@ConfigurationProperties(prefix = "veto.bus")
public class BusConfiguration {

    private WebSocketConfig websocket = new WebSocketConfig();
    private GrpcConfig grpc = new GrpcConfig();

    public WebSocketConfig getWebsocket() { return websocket; }
    public void setWebsocket(WebSocketConfig websocket) { this.websocket = websocket; }
    public GrpcConfig getGrpc() { return grpc; }
    public void setGrpc(GrpcConfig grpc) { this.grpc = grpc; }

    public static class WebSocketConfig {
        private int port = 9090;
        private String path = "/veto/bus";
        private int heartbeatIntervalMs = 30000;
        private int reconnectDelayMs = 5000;
        private int maxReconnectAttempts = 10;

        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
        public String getPath() { return path; }
        public void setPath(String path) { this.path = path; }
        public int getHeartbeatIntervalMs() { return heartbeatIntervalMs; }
        public void setHeartbeatIntervalMs(int heartbeatIntervalMs) { this.heartbeatIntervalMs = heartbeatIntervalMs; }
        public int getReconnectDelayMs() { return reconnectDelayMs; }
        public void setReconnectDelayMs(int reconnectDelayMs) { this.reconnectDelayMs = reconnectDelayMs; }
        public int getMaxReconnectAttempts() { return maxReconnectAttempts; }
        public void setMaxReconnectAttempts(int maxReconnectAttempts) { this.maxReconnectAttempts = maxReconnectAttempts; }
    }

    public static class GrpcConfig {
        private int port = 9091;
        private int maxMessageSize = 4194304;

        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
        public int getMaxMessageSize() { return maxMessageSize; }
        public void setMaxMessageSize(int maxMessageSize) { this.maxMessageSize = maxMessageSize; }
    }
}
