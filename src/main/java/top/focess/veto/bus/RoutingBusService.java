package top.focess.veto.bus;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import top.focess.veto.model.DAGPayload;

/**
 * C3 Communication & Routing Bus - top-level service. Orchestrates WebSocket transport, DAG payload
 * routing, and lifecycle. This is the single umbilical cord between the Veto client and the cloud
 * backend.
 */
@Service
public class RoutingBusService {

  private static final Logger log = LoggerFactory.getLogger(RoutingBusService.class);

  private final WebSocketBus webSocketBus;
  private final BusConfiguration config;
  private final ObjectMapper objectMapper;

  private final ConcurrentMap<String, DAGPayload> activePayloads = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, CompletableFuture<DAGPayload>> pendingFutures =
      new ConcurrentHashMap<>();

  public RoutingBusService(
      WebSocketBus webSocketBus, BusConfiguration config, ObjectMapper objectMapper) {
    this.webSocketBus = webSocketBus;
    this.config = config;
    this.objectMapper = objectMapper;
  }

  @PostConstruct
  public void init() {
    // Register fallback route for unhandled messages
    webSocketBus.registerMessageRoute(
        "fallback",
        payload -> {
          log.debug("C3 Bus: Fallback message received ({} bytes)", payload.length());
        });

    log.info(
        "C3 RoutingBusService: Initialized. WS port={}, gRPC port={}",
        config.getWebsocket().getPort(),
        config.getGrpc().getPort());
  }

  @PreDestroy
  public void shutdown() {
    webSocketBus.disconnect();
    log.info("C3 RoutingBusService: Shut down");
  }

  /** Submit a DAG payload to the cloud backend and return a future for the response. */
  public CompletableFuture<DAGPayload> submitDAGPayload(DAGPayload payload) {
    CompletableFuture<DAGPayload> future = new CompletableFuture<>();
    activePayloads.put(payload.getId(), payload);
    pendingFutures.put(payload.getId(), future);

    webSocketBus.sendDAGPayload(payload);

    // Timeout
    future
        .orTimeout(120, TimeUnit.SECONDS)
        .whenComplete(
            (result, error) -> {
              pendingFutures.remove(payload.getId());
              activePayloads.remove(payload.getId());
            });

    return future;
  }

  /** Connect to the cloud backend. */
  public CompletableFuture<Boolean> connect(String backendUrl) {
    return webSocketBus.connect(backendUrl);
  }

  /** Disconnect from the cloud backend. */
  public void disconnect() {
    webSocketBus.disconnect();
  }

  public boolean isConnected() {
    return webSocketBus.isConnected();
  }

  public int getActivePayloadCount() {
    return activePayloads.size();
  }
}
