package top.focess.veto.bus;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Exponential backoff reconnection handler for C3 Communication Bus. */
@Component
public class ReconnectionHandler {

    private static final Logger log = LoggerFactory.getLogger(ReconnectionHandler.class);

    private final BusConfiguration config;
    private final AtomicInteger reconnectAttempts = new AtomicInteger(0);
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(
                    r -> {
                        Thread t = new Thread(r, "veto-reconnect");
                        t.setDaemon(true);
                        return t;
                    });

    private volatile String lastBackendUrl;
    private volatile boolean reconnecting = false;

    public ReconnectionHandler(BusConfiguration config) {
        this.config = config;
    }

    /**
     * Schedule an exponential-backoff reconnection attempt.
     */
    public void scheduleReconnect(WebSocketBus bus, String backendUrl) {
        if (backendUrl == null || backendUrl.isEmpty()) {
            log.warn("C3 Reconnect: No backend URL to reconnect to");
            return;
        }
        this.lastBackendUrl = backendUrl;

        int attempt = reconnectAttempts.incrementAndGet();
        int maxAttempts = config.getWebsocket().getMaxReconnectAttempts();

        if (attempt > maxAttempts) {
            log.error("C3 Reconnect: Exhausted {} reconnect attempts. Giving up.", maxAttempts);
            reconnectAttempts.set(0);
            return;
        }

        long delay =
                (long) (config.getWebsocket().getReconnectDelayMs() * Math.pow(2, attempt - 1));
        delay = Math.min(delay, 120_000); // Cap at 2 minutes

        reconnecting = true;
        log.info("C3 Reconnect: Scheduling attempt {}/{} in {}ms", attempt, maxAttempts, delay);

        scheduler.schedule(
                () -> {
                    log.info("C3 Reconnect: Attempt {}/{} ...", attempt, maxAttempts);
                    bus.connect(backendUrl)
                            .thenAccept(
                                    success -> {
                                        if (success) {
                                            reconnectAttempts.set(0);
                                            reconnecting = false;
                                            log.info(
                                                    "C3 Reconnect: Successfully reconnected on attempt {}",
                                                    attempt);
                                        }
                                    });
                },
                delay,
                TimeUnit.MILLISECONDS);
    }

    /** Reset the reconnection state (call after successful initial connect). */
    public void reset() {
        reconnectAttempts.set(0);
        reconnecting = false;
    }

    public boolean isReconnecting() {
        return reconnecting;
    }

    public int getAttemptCount() {
        return reconnectAttempts.get();
    }

    public String getLastBackendUrl() {
        return lastBackendUrl;
    }
}
