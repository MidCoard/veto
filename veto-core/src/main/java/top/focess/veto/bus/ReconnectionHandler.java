package top.focess.veto.bus;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

/** Application retry policy; each attempt creates a fresh protocol connection. */
@Component
public class ReconnectionHandler {
    private final @NonNull BusConfiguration config;
    private final @NonNull ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(
                    r -> {
                        var thread = new Thread(r, "veto-reconnect");
                        thread.setDaemon(true);
                        return thread;
                    });
    private ScheduledFuture<?> pending;
    private int attempts;
    private boolean stopped;

    public ReconnectionHandler(@NonNull BusConfiguration config) {
        this.config = config;
    }

    public synchronized void scheduleReconnect(@NonNull Runnable attempt) {
        if (stopped) return;
        if (++attempts > config.getWebsocket().getMaxReconnectAttempts()) return;
        if (pending != null) pending.cancel(false);
        long delay =
                (long)
                        Math.min(
                                120_000,
                                config.getWebsocket().getReconnectDelayMs()
                                        * Math.pow(2, attempts - 1));
        pending = scheduler.schedule(attempt, delay, TimeUnit.MILLISECONDS);
    }

    public synchronized void reset() {
        if (pending != null) pending.cancel(false);
        pending = null;
        attempts = 0;
    }

    @PreDestroy
    public synchronized void shutdown() {
        stopped = true;
        reset();
        scheduler.shutdown();
    }
}
