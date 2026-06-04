package top.focess.veto.terminal;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import top.focess.scheduler.ThreadPoolScheduler;
import top.focess.veto.command.CommandRegistry;
import top.focess.veto.command.TerminalIO;
import top.focess.veto.contract.TerminalRequest;
import top.focess.veto.contract.TerminalResponse;
import top.focess.veto.vault.CredentialVaultConfiguration;

/**
 * File-based IPC channel between the terminal frontend and the command backend.
 *
 * <p>Watches {@code ~/.veto/terminal/in/} for new request files, dispatches each to {@link
 * CommandRegistry}, and writes the response to {@code ~/.veto/terminal/out/}. A {@link Semaphore}
 * caps the number of concurrently executing terminal sessions so the backend is never overwhelmed.
 */
@Component
public class TerminalChannel {

    private static final Logger log = LoggerFactory.getLogger(TerminalChannel.class);

    private final ObjectMapper json = new ObjectMapper();
    private final CredentialVaultConfiguration config;
    private final CommandRegistry registry;
    private final ThreadPoolScheduler scheduler;
    private final Map<String, TerminalIO> pending = new ConcurrentHashMap<>();
    private Semaphore connectionLimiter = new Semaphore(16);

    private volatile boolean running;

    @Value("${veto.terminal.max-concurrent-connections:16}")
    private int maxConcurrentConnections;

    @Value("${veto.terminal.request-timeout-ms:60000}")
    private long requestTimeoutMs;

    @Value("${veto.terminal.watch-poll-interval-ms:200}")
    private long watchPollIntervalMs;

    @Value("${veto.terminal.stale-pending-ttl-ms:300000}")
    private long stalePendingTtlMs;

    public TerminalChannel(CredentialVaultConfiguration config, CommandRegistry registry) {
        this.config = config;
        this.registry = registry;
        this.scheduler = new ThreadPoolScheduler("veto-terminal", 4);
    }

    @PostConstruct
    public void start() {
        this.connectionLimiter = new Semaphore(Math.max(1, maxConcurrentConnections));
        running = true;
        scheduler.run(this::watch, "terminal-channel");
        scheduler.runTimer(
                this::cleanupStalePending,
                Duration.ofMillis(stalePendingTtlMs),
                Duration.ofMillis(stalePendingTtlMs),
                "terminal-cleanup");
        log.info(
                "Terminal channel started (max-connections={}, request-timeout={}ms)",
                maxConcurrentConnections,
                requestTimeoutMs);
    }

    @PreDestroy
    public void stop() {
        running = false;
        scheduler.shutdown();
        log.info("Terminal channel stopped");
    }

    /**
     * Main watch loop — scans the {@code in/} directory for new request files and dispatches them
     * asynchronously. A {@link Semaphore} gates entry so that at most {@code
     * maxConcurrentConnections} requests execute at once.
     */
    private void watch() {
        Path inDir = Path.of(config.getVaultHome(), "terminal", "in");
        Path outDir = Path.of(config.getVaultHome(), "terminal", "out");
        try {
            Files.createDirectories(inDir);
            Files.createDirectories(outDir);
            lockdown(inDir);
            lockdown(outDir);
        } catch (IOException e) {
            log.error("Cannot create terminal I/O directories", e);
            return;
        }

        while (running) {
            try (var stream = Files.newDirectoryStream(inDir, "*.json")) {
                for (Path file : stream) {
                    if (!running) break;
                    String filename = file.getFileName().toString();

                    // Follow-up input from an in-progress PROMPT interaction
                    if (filename.contains("-next")) {
                        handleFollowUp(file, filename);
                        continue;
                    }

                    // New top-level request
                    handleNewRequest(file, filename, inDir, outDir);
                }
            } catch (IOException e) {
                log.warn("Scan of {} failed", inDir, e);
            }

            try {
                Thread.sleep(watchPollIntervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void handleFollowUp(Path file, String filename) {
        String requestId = filename.replace("-next.json", "");
        TerminalIO io = pending.get(requestId);
        if (io == null) {
            // Request already completed/abandoned — clean up the orphaned follow-up
            log.debug("No pending request for follow-up {} — removing", requestId);
            try {
                Files.deleteIfExists(file);
            } catch (IOException ignored) {
            }
            return;
        }
        // Feed the input directly — io.input(String) sets flag=true and notifyAll(),
        // which wakes up the command thread blocked in io.input(long).
        // Do NOT call io.hasInput() here; it would deadlock because hasInput()
        // waits for flag==true, which only io.input(String) can set.
        try {
            TerminalRequest fu = json.readValue(file.toFile(), TerminalRequest.class);
            Files.delete(file);
            io.input(fu.raw().trim());
        } catch (Exception e) {
            log.error("Failed to feed follow-up for {}", requestId, e);
            try {
                Files.deleteIfExists(file);
            } catch (IOException ignored) {
            }
        }
    }

    private void handleNewRequest(Path file, String filename, Path inDir, Path outDir) {
        String requestId = filename.replace(".json", "");
        boolean acquired = false;
        try {
            TerminalRequest req = json.readValue(file.toFile(), TerminalRequest.class);
            Files.delete(file);

            // Try to acquire a permit; if none available, return an error immediately
            if (!connectionLimiter.tryAcquire()) {
                log.warn(
                        "Connection limit reached ({}) — rejecting {}",
                        maxConcurrentConnections,
                        requestId);
                TerminalResponse busy =
                        TerminalResponse.error(
                                "Server busy — too many terminal connections. "
                                        + "Try again shortly.");
                json.writeValue(outDir.resolve(requestId + ".json").toFile(), busy);
                return;
            }
            acquired = true;

            TerminalIO io = new TerminalIO(outDir, requestId);
            pending.put(requestId, io);

            scheduler.submit(
                    () -> {
                        try {
                            TerminalResponse resp =
                                    registry.dispatch(req.raw(), req.sessionToken(), io);
                            // Only write the response if the command did NOT already write one
                            // via io.respond(). If it did, writing again would corrupt the
                            // PROMPT/follow-up protocol: the terminal already consumed the
                            // response, deleted the file, and sent the follow-up. A re-write
                            // would inject a stale response that the terminal misreads.
                            if (!io.hasResponded()) {
                                Path outFile = outDir.resolve(requestId + ".json");
                                json.writeValue(outFile.toFile(), resp);
                            }
                        } catch (Exception e) {
                            log.error("Dispatch failed for {}", requestId, e);
                            if (!io.hasResponded()) {
                                try {
                                    Path outFile = outDir.resolve(requestId + ".json");
                                    json.writeValue(
                                            outFile.toFile(),
                                            TerminalResponse.error(
                                                    "Internal error: " + e.getMessage()));
                                } catch (Exception inner) {
                                    log.error("Failed to write error response", inner);
                                }
                            }
                        } finally {
                            pending.remove(requestId);
                            connectionLimiter.release();
                        }
                        return null;
                    },
                    "cmd-" + requestId.substring(0, Math.min(8, requestId.length())));
        } catch (Exception e) {
            log.error("Failed processing request {}", requestId, e);
            if (acquired) {
                connectionLimiter.release();
            }
            try {
                Files.deleteIfExists(file);
            } catch (IOException ignored) {
            }
        }
    }

    /**
     * Periodically removes pending entries that have been idle for longer than {@code
     * stalePendingTtlMs} without receiving a follow-up.
     */
    private void cleanupStalePending() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, TerminalIO>> it = pending.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, TerminalIO> entry = it.next();
            TerminalIO io = entry.getValue();
            if (io.isStale(stalePendingTtlMs)) {
                log.debug("Removing stale pending request {}", entry.getKey());
                it.remove();
                connectionLimiter.release();
            }
        }
    }

    /**
     * Exposed for health checks.
     */
    public int activeConnections() {
        return pending.size();
    }

    /**
     * Exposed for health checks.
     */
    public int availablePermits() {
        return connectionLimiter.availablePermits();
    }

    // ── File-permission lockdown ──────────────────────────────────────────────

    /**
     * Best-effort owner-only permissions on the directory and its vault-root ancestors. On POSIX
     * this means {@code 0700}; on Windows it replaces the ACL with owner-full-control only.
     */
    private void lockdown(Path dir) {
        Path vaultRoot = Path.of(config.getVaultHome());
        Path p = dir;
        while (p != null && p.startsWith(vaultRoot)) {
            try {
                if (Files.getFileStore(p).supportsFileAttributeView("posix")) {
                    Set<PosixFilePermission> perms =
                            Files.isDirectory(p)
                                    ? EnumSet.of(
                                    PosixFilePermission.OWNER_READ,
                                    PosixFilePermission.OWNER_WRITE,
                                    PosixFilePermission.OWNER_EXECUTE)
                                    : EnumSet.of(
                                    PosixFilePermission.OWNER_READ,
                                    PosixFilePermission.OWNER_WRITE);
                    Files.setPosixFilePermissions(p, perms);
                } else {
                    AclFileAttributeView acl =
                            Files.getFileAttributeView(p, AclFileAttributeView.class);
                    if (acl != null) {
                        var entry =
                                AclEntry.newBuilder()
                                        .setType(AclEntryType.ALLOW)
                                        .setPrincipal(acl.getOwner())
                                        .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                                        .build();
                        acl.setAcl(java.util.List.of(entry));
                    }
                }
            } catch (Exception e) {
                log.debug("Could not lock down {}: {}", p, e.getMessage());
            }
            if (p.equals(vaultRoot)) break;
            p = p.getParent();
        }
    }
}
