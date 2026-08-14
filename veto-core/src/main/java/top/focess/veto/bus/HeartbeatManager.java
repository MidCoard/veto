package top.focess.veto.bus;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Heartbeat manager for bus Communication Bus. Sends periodic heartbeat pings and monitors
 * connection health.
 */
@Component
public class HeartbeatManager {

    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.bus.HeartbeatManager");

    private final @NonNull BusConfiguration config;
    private final @NonNull AtomicInteger heartbeatCount = new AtomicInteger(0);
    private final @NonNull AtomicLong lastHeartbeatAck = new AtomicLong(0);
    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> heartbeatFuture;
    private volatile WebSocketBus bus;

    public HeartbeatManager(@NonNull BusConfiguration config) {
        this.config = config;
    }

    /** Start sending heartbeats at the configured interval. */
    public synchronized void start(@NonNull WebSocketBus bus) {
        this.bus = bus;
        if (heartbeatFuture != null && !heartbeatFuture.isCancelled()) {
            return;
        }
        this.scheduler =
                Executors.newSingleThreadScheduledExecutor(
                        r -> {
                            Thread t = new Thread(r, "veto-heartbeat");
                            t.setDaemon(true);
                            return t;
                        });
        this.lastHeartbeatAck.set(System.currentTimeMillis());

        int intervalMs = config.getWebsocket().getHeartbeatIntervalMs();
        ScheduledExecutorService activeScheduler = scheduler;
        if (activeScheduler == null) {
            throw new IllegalStateException("heartbeat scheduler was not initialized");
        }
        heartbeatFuture =
                activeScheduler.scheduleAtFixedRate(
                        this::sendHeartbeat, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        log.info("bus Heartbeat: Started (interval={}ms)", intervalMs);
    }

    /** Stop sending heartbeats. */
    public synchronized void stop() {
        if (heartbeatFuture != null) {
            heartbeatFuture.cancel(false);
            heartbeatFuture = null;
        }
        if (scheduler != null) {
            scheduler.shutdown();
            scheduler = null;
        }
        log.info("bus Heartbeat: Stopped (sent {} total)", heartbeatCount.get());
    }

    private void sendHeartbeat() {
        try {
            WebSocketBus activeBus = bus;
            if (activeBus != null && activeBus.isConnected()) {
                activeBus.sendMessage(
                        "{\"type\":\"heartbeat\",\"seq\":"
                                + heartbeatCount.incrementAndGet()
                                + "}");
                log.trace("bus Heartbeat: Sent seq={}", heartbeatCount.get());
            }
        } catch (Exception e) {
            log.warn("bus Heartbeat: Failed to send", e);
        }

        // Check for stale connection (no ack in 3x interval)
        long staleThreshold = config.getWebsocket().getHeartbeatIntervalMs() * 3L;
        long elapsed = System.currentTimeMillis() - lastHeartbeatAck.get();
        if (elapsed > staleThreshold) {
            log.warn("bus Heartbeat: No ack for {}ms, connection may be stale", elapsed);
        }
    }

    /** Record a heartbeat acknowledgment from the server. */
    public void recordAck() {
        lastHeartbeatAck.set(System.currentTimeMillis());
    }

    public int getHeartbeatCount() {
        return heartbeatCount.get();
    }

    public long getMsSinceLastAck() {
        return System.currentTimeMillis() - lastHeartbeatAck.get();
    }
}
