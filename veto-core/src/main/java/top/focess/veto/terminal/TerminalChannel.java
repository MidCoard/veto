package top.focess.veto.terminal;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import top.focess.scheduler.ThreadPoolScheduler;
import top.focess.veto.command.CommandRegistry;
import top.focess.veto.contract.IpcChannel;
import top.focess.veto.contract.IpcFile;
import top.focess.veto.contract.IpcFrame;
import top.focess.veto.vault.CredentialVaultConfiguration;

/**
 * Backend IPC server. Each terminal connection uses two unidirectional files:
 *
 * <ul>
 *   <li>{@code <id>-req.ipc} — terminal writes requests, backend reads them
 *   <li>{@code <id>-resp.ipc} — backend writes responses, terminal reads them
 * </ul>
 *
 * <p>The backend sends streaming deltas, prompts, and terminal frames on the resp channel while
 * still listening for {@code cancel}, {@code heartbeat}, and {@code bye} on the req channel —
 * independent, no contention.
 */
@Component
public class TerminalChannel {

    private static final Logger log = LoggerFactory.getLogger(TerminalChannel.class);

    private static final Duration POLL_INTERVAL = Duration.ofMillis(50);
    private static final Duration RECEIVE_TIMEOUT = Duration.ofSeconds(90);
    private static final Duration HEARTBEAT_DEADLINE = Duration.ofSeconds(90);
    private static final int IPC_FILE_SIZE_KB = 256;
    private static final int IPC_FILE_SIZE = IPC_FILE_SIZE_KB * 1024;

    private final CredentialVaultConfiguration config;
    private final CommandRegistry registry;
    private final ThreadPoolScheduler scheduler;
    private final Map<String, ServerSession> activeSessions = new ConcurrentHashMap<>();
    private Semaphore connectionLimiter = new Semaphore(16);

    private volatile boolean running;

    @Value("${veto.terminal.max-concurrent-connections:16}")
    private int maxConcurrentConnections;

    public TerminalChannel(CredentialVaultConfiguration config, CommandRegistry registry) {
        this.config = config;
        this.registry = registry;
        this.scheduler = new ThreadPoolScheduler("veto-terminal", 4);
    }

    @PostConstruct
    public void start() {
        connectionLimiter = new Semaphore(Math.max(1, maxConcurrentConnections));
        running = true;
        scheduler.run(this::discoveryLoop, "terminal-discovery");
        log.info("Terminal IPC server started (max={})", maxConcurrentConnections);
    }

    @PreDestroy
    public void stop() {
        running = false;
        scheduler.shutdown();
        for (ServerSession session : activeSessions.values()) {
            session.close();
        }
        log.info("Terminal IPC server stopped");
    }

    // ── Discovery ───────────────────────────────────────────────────────

    private Path terminalDir() {
        return Path.of(config.getVaultHome(), "terminal");
    }

    private void discoveryLoop() {
        Path dir = terminalDir();
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            log.error("Cannot create terminal dir {}", dir, e);
            return;
        }

        processExisting(dir);

        try (WatchService watcher = FileSystems.getDefault().newWatchService()) {
            dir.register(watcher, StandardWatchEventKinds.ENTRY_CREATE);

            while (running) {
                WatchKey key;
                try {
                    key = watcher.poll(100, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (key == null) continue;

                for (WatchEvent<?> event : key.pollEvents()) {
                    if (!running) break;
                    if (event.kind() == StandardWatchEventKinds.OVERFLOW) continue;
                    Path name = (Path) event.context();
                    if (name == null) continue;
                    String filename = name.toString();
                    // Only react to -req files — the resp file is created later in accept()
                    if (!filename.endsWith("-req.ipc")) continue;

                    Path file = dir.resolve(name);
                    if (!Files.exists(file)) continue;
                    accept(file);
                }
                key.reset();
            }
        } catch (IOException e) {
            log.error("WatchService failed", e);
        }
    }

    private void processExisting(Path dir) {
        try (var stream = Files.newDirectoryStream(dir, "*-req.ipc")) {
            for (Path file : stream) accept(file);
        } catch (IOException e) {
            log.warn("Could not process existing ipc files", e);
        }
    }

    // ── Connection management ───────────────────────────────────────────

    private void accept(Path reqFile) {
        String baseName = reqFile.getFileName().toString();
        String terminalId = baseName.replace("-req.ipc", "");

        if (activeSessions.containsKey(terminalId)) return;

        if (!connectionLimiter.tryAcquire()) {
            log.warn(
                    "Connection limit reached ({}) — rejecting {}",
                    maxConcurrentConnections,
                    terminalId);
            try {
                Path respFile = terminalDir().resolve(terminalId + "-resp.ipc");
                IpcChannel resp =
                        new IpcChannel(new IpcFile(respFile, IPC_FILE_SIZE), POLL_INTERVAL);
                resp.send(
                        new IpcFrame.Error(
                                "Server busy — too many connections (max "
                                        + maxConcurrentConnections
                                        + "). Try again later."));
                resp.close();
                Files.deleteIfExists(respFile);
            } catch (IOException ignored) {
            }
            return;
        }

        try {
            Path respFile = terminalDir().resolve(terminalId + "-resp.ipc");
            IpcChannel reqChannel =
                    new IpcChannel(new IpcFile(reqFile, IPC_FILE_SIZE), POLL_INTERVAL);
            IpcChannel respChannel =
                    new IpcChannel(new IpcFile(respFile, IPC_FILE_SIZE), POLL_INTERVAL);
            ServerSession session = new ServerSession(terminalId, reqChannel, respChannel);
            activeSessions.put(terminalId, session);
            scheduler.submit(
                    () -> serveLoop(session),
                    "term-" + terminalId.substring(0, Math.min(8, terminalId.length())));
            log.debug("Accepted terminal {}", terminalId);
        } catch (IOException e) {
            log.error("Failed to open IPC files for {}", terminalId, e);
            connectionLimiter.release();
        }
    }

    // ── Serve loop ──────────────────────────────────────────────────────

    private Void serveLoop(ServerSession session) {
        IpcChannel reqChannel = session.reqChannel;
        IpcChannel respChannel = session.respChannel;
        String terminalId = session.terminalId;

        while (running) {
            try {
                IpcFrame frame = reqChannel.receive(RECEIVE_TIMEOUT);

                if (frame == null) {
                    long now = System.nanoTime();
                    if (now - session.lastHeartbeatNanos > HEARTBEAT_DEADLINE.toNanos()) {
                        log.debug(
                                "Terminal {} heartbeat deadline exceeded — cleaning up",
                                terminalId);
                        break;
                    }
                    continue;
                }

                switch (frame) {
                    case IpcFrame.Heartbeat h -> session.lastHeartbeatNanos = System.nanoTime();
                    case IpcFrame.Bye b -> {
                        log.debug("Terminal {} sent bye — cleaning up", terminalId);
                        removeSession(session);
                        return null;
                    }
                    case IpcFrame.Request req -> {
                        session.lastHeartbeatNanos = System.nanoTime();
                        registry.dispatch(terminalId, req.raw(), reqChannel, respChannel);
                        session.lastHeartbeatNanos = System.nanoTime();
                    }
                    case IpcFrame.Complete comp -> {
                        session.lastHeartbeatNanos = System.nanoTime();
                        List<String> completions = registry.complete(terminalId, comp.raw());
                        respChannel.send(IpcFrame.doneContent(String.join("\n", completions)));
                        session.lastHeartbeatNanos = System.nanoTime();
                    }
                    case IpcFrame.Cancel c -> {
                        session.lastHeartbeatNanos = System.nanoTime();
                        respChannel.send(new IpcFrame.Done(Map.of("cancelled", true)));
                    }
                    case IpcFrame.Hint h -> {
                        session.lastHeartbeatNanos = System.nanoTime();
                        String[] hint = registry.hint(terminalId, h.raw());
                        if (hint[0] != null && !hint[0].isBlank()) {
                            respChannel.send(
                                    new IpcFrame.Done(
                                            hint[1] != null
                                                    ? Map.of("description", hint[1])
                                                    : Map.of(),
                                            hint[0]));
                        } else {
                            respChannel.send(new IpcFrame.Done(Map.of(), ""));
                        }
                    }
                    default -> {}
                }
            } catch (IOException e) {
                log.warn("I/O error for terminal {}: {}", terminalId, e.getMessage());
                break;
            }
        }

        removeSession(session);
        return null;
    }

    private void removeSession(ServerSession session) {
        activeSessions.remove(session.terminalId);
        connectionLimiter.release();
        session.close();
        log.debug("Removed terminal {}", session.terminalId);
    }

    public int activeConnections() {
        return activeSessions.size();
    }

    // ── ServerSession ───────────────────────────────────────────────────

    private static class ServerSession {
        final String terminalId;
        final IpcChannel reqChannel;
        final IpcChannel respChannel;
        volatile long lastHeartbeatNanos = System.nanoTime();

        ServerSession(String terminalId, IpcChannel reqChannel, IpcChannel respChannel) {
            this.terminalId = terminalId;
            this.reqChannel = reqChannel;
            this.respChannel = respChannel;
        }

        void close() {
            try {
                reqChannel.close();
            } catch (IOException ignored) {
            }
            try {
                respChannel.close();
            } catch (IOException ignored) {
            }
        }
    }
}
