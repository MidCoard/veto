package top.focess.veto.terminal;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.*;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;
import top.focess.veto.command.CommandRegistry;
import top.focess.veto.command.TerminalSessionManager;
import top.focess.veto.command.VetoCommandSender;
import top.focess.veto.contract.HintInfo;
import top.focess.veto.contract.IpcFrame;
import top.focess.veto.contract.IpcMeta;
import top.focess.veto.contract.ZmqTransport;

/**
 * ZeroMQ-based IPC server replacing the old file-based {@code TerminalChannel}.
 *
 * <h3>Thread model</h3>
 *
 * <ul>
 *   <li><b>One IO thread</b> — runs {@link #ioLoop()}, the single thread that touches the ROUTER
 *       socket. Drains the outbox queue and sends responses.
 *   <li><b>Dispatch worker pool</b> — virtual threads. Each incoming {@code Request} or {@code
 *       Input} frame is dispatched to a worker for execution.
 *   <li><b>Heartbeat monitor</b> — one periodic thread that expires silent terminals.
 * </ul>
 *
 * <h3>Per-terminal sender</h3>
 *
 * Each connected terminal gets exactly one {@link VetoCommandSender} scoped to its identity. The
 * sender's {@link VetoCommandSender#output(String)} pushes responses onto the shared outbox; the IO
 * thread drains and sends them.
 */
@Component
@ConditionalOnProperty(name = "veto.terminal.enabled", havingValue = "true", matchIfMissing = true)
public class ZmqServer {

    private static final Logger log = LoggerFactory.getLogger(ZmqServer.class);

    private static final long HEARTBEAT_INTERVAL_MS = 30_000;
    private static final long SESSION_TIMEOUT_MS = 90_000;
    private static final int MAX_OUTBOX_SIZE = 10_000;

    private final CommandRegistry registry;
    private final TerminalSessionManager sessionManager;
    private final ConcurrentLinkedQueue<OutboxEntry> outbox = new ConcurrentLinkedQueue<>();
    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Future<?>> activeTasks = new ConcurrentHashMap<>();
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();

    private ZContext ctx;
    private ZmqTransport transport;
    private volatile boolean running;

    @Value("${veto.terminal.bind-address:tcp://127.0.0.1:5555}")
    private String bindAddress;

    public ZmqServer(
            @NotNull CommandRegistry registry, @NotNull TerminalSessionManager sessionManager) {
        this.registry = registry;
        this.sessionManager = sessionManager;
    }

    @PostConstruct
    public void start() {
        ctx = new ZContext();
        transport = ZmqTransport.bindRouter(ctx, bindAddress);
        running = true;
        workers.submit(this::ioLoop);
        workers.submit(this::heartbeatLoop);
        log.info("ZmqServer bound to {}", bindAddress);
    }

    @PreDestroy
    public void stop() {
        running = false;
        workers.shutdown();
        try {
            workers.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        transport.close();
        ctx.close();
        log.info("ZmqServer stopped");
    }

    // ── IO loop (single thread touching the socket) ──────────────────────

    private void ioLoop() {
        ZMQ.Poller poller = ctx.createPoller(1);
        poller.register(transport.socket, ZMQ.Poller.POLLIN);

        while (running) {
            // 1. Block until data arrives or 50 ms elapses
            poller.poll(50);

            // 2. Read incoming
            ZmqTransport.ZmqMessage msg = null;
            if (poller.pollin(0)) {
                msg = transport.tryReceive();
            }
            if (msg != null) {
                String identity = msg.identity();
                IpcFrame frame = msg.frame();
                if (frame == null) {
                    log.warn("Corrupt frame from {}", identity);
                } else {
                    Future<?> task =
                            workers.submit(
                                    () -> {
                                        try {
                                            handleFrame(identity, frame);
                                        } catch (Throwable t) {
                                            log.error("Unhandled exception for {}", identity, t);
                                            offerToOutbox(
                                                    new OutboxEntry(
                                                            identity,
                                                            new IpcFrame.Error(
                                                                    "Internal error: "
                                                                            + t.getMessage())));
                                        }
                                    });
                    if (frame instanceof IpcFrame.Request) {
                        activeTasks.put(identity, task);
                    }
                }
            }

            // 3. Drain outbox
            OutboxEntry entry;
            while ((entry = outbox.poll()) != null) {
                try {
                    log.info(
                            "IO drain: {} → {}",
                            entry.frame.getClass().getSimpleName(),
                            entry.identity);
                    transport.send(entry.identity, entry.frame);
                } catch (Exception e) {
                    log.warn("Failed to send to {}", entry.identity, e);
                }
            }
        }
        poller.close();
    }

    // ── frame dispatch ───────────────────────────────────────────────────

    private void handleFrame(@NotNull String identity, @NotNull IpcFrame frame) {
        Session session =
                sessions.computeIfAbsent(identity, id -> new Session(id, createSender(id)));

        switch (frame) {
            case IpcFrame.Request req -> {
                try {
                    log.debug("REQ  {}: {}", identity.substring(0, 8), req.raw());
                    session.lastActivityNanos = System.nanoTime();
                    session.sender.setOutbox(outbox, identity);
                    registry.dispatch(session.sender, req.raw());
                    session.lastActivityNanos = System.nanoTime();
                    IpcFrame terminal =
                            session.sender.hasError()
                                    ? new IpcFrame.Error("Command failed.", req.seq())
                                    : new IpcFrame.Done(session.sender.doneMeta(), null, req.seq());
                    offerToOutbox(new OutboxEntry(identity, terminal));
                    log.debug(
                            "DONE {}: {} {}",
                            identity.substring(0, 8),
                            terminal instanceof IpcFrame.Error ? "Error" : "Done",
                            session.sender.doneMeta());
                } finally {
                    activeTasks.remove(identity);
                }
            }

            case IpcFrame.Input in -> {
                log.debug("IN   {}: {}", identity.substring(0, 8), in.raw());
                session.lastActivityNanos = System.nanoTime();
                boolean accepted = session.sender.receiveInput(in.raw());
                if (!accepted) {
                    log.debug("Stale input from {}: no future waiting", identity);
                    offerToOutbox(
                            new OutboxEntry(
                                    identity,
                                    new IpcFrame.Error(
                                            "Input no longer expected"
                                                    + " — request may have timed out.")));
                }
            }

            case IpcFrame.Complete comp -> {
                log.debug("COMP {}: {}", identity.substring(0, 8), comp.raw());
                session.lastActivityNanos = System.nanoTime();
                var completions = registry.complete(identity, comp.raw());
                log.debug(
                        "COMP {}: -> {} candidates", identity.substring(0, 8), completions.size());
                offerToOutbox(
                        new OutboxEntry(
                                identity,
                                IpcFrame.doneContent(String.join("\n", completions), comp.seq())));
            }

            case IpcFrame.Hint h -> {
                log.debug("HINT {}: {}", identity.substring(0, 8), h.raw());
                session.lastActivityNanos = System.nanoTime();
                HintInfo hint = registry.hint(identity, h.raw());
                Map<String, Object> meta = new HashMap<>();
                meta.put(IpcMeta.IS_HINT, true);
                if (hint.description() != null) meta.put(IpcMeta.DESCRIPTION, hint.description());

                offerToOutbox(
                        new OutboxEntry(
                                identity,
                                new IpcFrame.Done(
                                        meta,
                                        hint.placeholder() != null ? hint.placeholder() : "",
                                        h.seq())));
            }

            case IpcFrame.Cancel c -> {
                log.debug("CANC {}: request cancelled", identity.substring(0, 8));
                session.lastActivityNanos = System.nanoTime();
                // Interrupt the in-flight Request task
                Future<?> task = activeTasks.remove(identity);
                if (task != null) {
                    task.cancel(true);
                }
                session.sender.cancelPendingInput();
                offerToOutbox(
                        new OutboxEntry(
                                identity, new IpcFrame.Done(Map.of(IpcMeta.CANCELLED, true))));
            }

            case IpcFrame.Bye b -> {
                log.debug("BYE  {}: terminal disconnecting", identity.substring(0, 8));
                offerToOutbox(
                        new OutboxEntry(identity, new IpcFrame.Done(Map.of(IpcMeta.EXIT, true))));
                activeTasks.remove(identity);
                sessions.remove(identity);
                sessionManager.invalidate(identity);
            }

            case IpcFrame.Hello hello -> {
                // Evict old session if this identity is reconnecting
                if (sessions.containsKey(identity)) {
                    log.warn(
                            "Duplicate identity {} — evicting old session",
                            identity.substring(0, 8));
                    activeTasks.remove(identity);
                    sessionManager.invalidate(identity);
                    sessions.remove(identity);
                }
                int negotiated = Math.min(hello.version(), IpcFrame.PROTOCOL_VERSION);
                log.debug(
                        "HELLO {}: v{} → negotiated v{}",
                        identity.substring(0, 8),
                        hello.version(),
                        negotiated);
                offerToOutbox(new OutboxEntry(identity, new IpcFrame.Welcome(negotiated)));
            }

            case IpcFrame.Heartbeat h -> {
                session.lastActivityNanos = System.nanoTime();
            }

            default -> {
                if (frame instanceof IpcFrame.Unknown u) {
                    log.warn(
                            "Unknown frame type '{}' from {} — protocol version mismatch?",
                            u.type(),
                            identity.substring(0, 8));
                }
            }
        }
    }

    // ── heartbeat monitor ────────────────────────────────────────────────

    private void heartbeatLoop() {
        while (running) {
            try {
                Thread.sleep(HEARTBEAT_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            long cutoff = System.nanoTime() - TimeUnit.MILLISECONDS.toNanos(SESSION_TIMEOUT_MS);
            Iterator<Map.Entry<String, Session>> it = sessions.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, Session> e = it.next();
                if (e.getValue().lastActivityNanos < cutoff) {
                    String id = e.getKey();
                    log.debug("Removing stale session {}", id);
                    activeTasks.remove(id);
                    sessionManager.invalidate(id);
                    it.remove();
                }
            }
        }
    }

    // ── sender factory ────────────────────────────────────────────────────

    @NotNull
    private VetoCommandSender createSender(@NotNull String identity) {
        VetoCommandSender sender =
                new VetoCommandSender(registry.resolveUsername(identity), identity);
        sender.setSessionResolver(registry::resolveUsername);
        return sender;
    }

    // ── outbox ────────────────────────────────────────────────────────────

    /** Offers an entry to the outbox, dropping it with a warning if the queue is congested. */
    private void offerToOutbox(@NotNull OutboxEntry entry) {
        if (outbox.size() > MAX_OUTBOX_SIZE) {
            log.warn(
                    "Outbox congested ({} entries), dropping frame {} for {}",
                    outbox.size(),
                    entry.frame().getClass().getSimpleName(),
                    entry.identity());
            return;
        }
        outbox.add(entry);
    }

    // ── types ────────────────────────────────────────────────────────────

    public record OutboxEntry(@NotNull String identity, @NotNull IpcFrame frame) {}

    static class Session {
        @NotNull final String identity;
        @NotNull final VetoCommandSender sender;
        volatile long lastActivityNanos = System.nanoTime();

        Session(@NotNull String identity, @NotNull VetoCommandSender sender) {
            this.identity = identity;
            this.sender = sender;
        }
    }
}
