package top.focess.veto.terminal;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
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
import top.focess.veto.command.VetoCommandSender;
import top.focess.veto.contract.IpcFrame;
import top.focess.veto.contract.IpcFrame.HintInfo;
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

    private static final long HEARTBEAT_INTERVAL_MS = 40_000;
    private static final long SESSION_TIMEOUT_MS = 90_000;
    private static final int MAX_OUTBOX_SIZE = 10_000;

    private final CommandRegistry registry;
    private final ConcurrentLinkedQueue<OutboxEntry> outbox = new ConcurrentLinkedQueue<>();
    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Future<?>> activeTasks = new ConcurrentHashMap<>();
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();

    private ZContext ctx;
    private ZmqTransport transport;
    private volatile boolean running;

    @Value("${veto.terminal.bind-address:tcp://127.0.0.1:5555}")
    private String bindAddress;

    public ZmqServer(@NotNull CommandRegistry registry) {
        this.registry = registry;
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
        for (String identity : sessions.keySet()) {
            send(identity, new IpcFrame.Terminate("Server shutting down."));
        }
        try {
            Thread.sleep(100);
        } catch (InterruptedException ignored) {
        }
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
            long timeout = outbox.isEmpty() ? 50 : 0;
            poller.poll(timeout);

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
                                            send(
                                                    identity,
                                                    IpcFrame.Error.ofError(
                                                            "Internal error: " + t.getMessage()));
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
                    log.trace("REQ  {}: {}", identity.substring(0, 8), req.raw());
                    session.lastActivityNanos = System.nanoTime();
                    session.sender.resetForDispatch();
                    registry.dispatch(session.sender, req.raw());
                    session.lastActivityNanos = System.nanoTime();
                    IpcFrame terminal;
                    if (session.sender.terminateReason() != null) {
                        terminal = new IpcFrame.Terminate(session.sender.terminateReason());
                    } else if (session.sender.hasError()) {
                        terminal = IpcFrame.Error.ofError("Command failed.");
                    } else {
                        terminal = new IpcFrame.Done(session.sender.doneMeta(), null);
                    }
                    send(identity, terminal);
                    log.trace(
                            "DONE {}: {} {}",
                            identity.substring(0, 8),
                            terminal instanceof IpcFrame.Error ? "Error" : "Done",
                            session.sender.doneMeta());
                } finally {
                    activeTasks.remove(identity);
                }
            }

            case IpcFrame.Input in -> {
                log.trace("IN   {}", identity.substring(0, 8));
                session.lastActivityNanos = System.nanoTime();
                boolean accepted = session.sender.receiveInput(in.raw());
                if (!accepted) {
                    log.trace("Stale input from {}: no future waiting", identity);
                    send(
                            identity,
                            IpcFrame.Error.ofError(
                                    "Input no longer expected — request may have timed out."));
                }
            }

            case IpcFrame.Complete comp -> {
                log.trace("COMP {}: {}", identity.substring(0, 8), comp.raw());
                session.lastActivityNanos = System.nanoTime();
                var completions = registry.complete(session.sender, comp.raw());
                log.trace(
                        "COMP {}: -> {} candidates", identity.substring(0, 8), completions.size());
                send(identity, new IpcFrame.CompleteResult(completions, comp.seq()));
            }

            case IpcFrame.Hint h -> {
                log.trace("HINT {}: {}", identity.substring(0, 8), h.raw());
                session.lastActivityNanos = System.nanoTime();
                HintInfo hint = registry.hint(session.sender, h.raw());
                send(identity, new IpcFrame.HintResult(hint, h.seq()));
            }

            case IpcFrame.Cancel c -> {
                log.trace("CANC {}: request cancelled", identity.substring(0, 8));
                session.lastActivityNanos = System.nanoTime();
                // Interrupt the in-flight Request task
                Future<?> task = activeTasks.remove(identity);
                if (task != null) {
                    task.cancel(true);
                }
                session.sender.cancelPendingInput();
                send(identity, new IpcFrame.Done(Map.of(IpcMeta.CANCELLED, true), null));
            }

            case IpcFrame.Bye b -> {
                log.trace("BYE  {}: terminal disconnecting", identity.substring(0, 8));
                send(identity, new IpcFrame.Done(Map.of(), null));
                Future<?> task = activeTasks.remove(identity);
                if (task != null) {
                    task.cancel(true);
                }
                sessions.remove(identity);
            }

            case IpcFrame.Hello hello -> {
                if (sessions.containsKey(identity)) {
                    log.warn(
                            "Duplicate identity {} — rejecting new connection",
                            identity.substring(0, 8));
                    send(
                            identity,
                            new IpcFrame.Error("Duplicate identity connected.", hello.seq()));
                } else {
                    int negotiated = Math.min(hello.version(), IpcFrame.PROTOCOL_VERSION);
                    log.trace(
                            "HELLO {}: v{} → negotiated v{}",
                            identity.substring(0, 8),
                            hello.version(),
                            negotiated);
                    send(identity, new IpcFrame.Welcome(negotiated, hello.seq()));
                }
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
                    Future<?> task = activeTasks.remove(id);
                    if (task != null) {
                        task.cancel(true);
                    }
                    it.remove();
                }
            }
        }
    }

    // ── sender factory ────────────────────────────────────────────────────

    @NotNull
    private VetoCommandSender createSender(@NotNull String identity) {
        return new VetoCommandSender(this, null, identity);
    }

    public void send(@NotNull String identity, @NotNull IpcFrame frame) {
        if (outbox.size() > MAX_OUTBOX_SIZE) {
            log.warn(
                    "Outbox congested ({} entries), dropping frame {} for {}",
                    outbox.size(),
                    frame.getClass().getSimpleName(),
                    identity);
            return;
        }
        outbox.add(new OutboxEntry(identity, frame));
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
