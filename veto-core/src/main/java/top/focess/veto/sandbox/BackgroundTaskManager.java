package top.focess.veto.sandbox;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Owns the lifecycle of detached ("background") processes launched by {@code run_task}. A
 * background task is a process the {@link SandboxSubstrate} starts but does <b>not</b> wait for -
 * the manager drains its merged stdout/stderr into a bounded ring buffer on a virtual thread and
 * tracks its exit, so the tool call returns immediately and the process survives across turns. The
 * agent inspects/stops it via the {@code view_task} / {@code stop_task} tools.
 *
 * <p>Tasks are scoped per agent: every method takes the calling {@code agentId} (threaded from
 * {@code ToolCallContextHolder} by the tools) and only touches that agent's tasks - a task's {@code
 * agentId} is fixed at {@link #start} and cross-agent access reports "not found". Tasks are
 * in-memory/volatile (like {@link SandboxHandle}s): a server restart orphans the OS processes.
 * {@link #stopAll(String)} (hooked into agent removal) + {@link #shutdown()} ({@code @PreDestroy})
 * clean them up.
 *
 * <p>Layering: lives in the sandbox package and takes {@code agentId} as a parameter so it does not
 * depend upward on {@code agent.mcp}.
 */
@Service
public class BackgroundTaskManager {

    private static final Logger log = LoggerFactory.getLogger(BackgroundTaskManager.class);

    /** Ring-buffer cap: the last N lines of merged output kept per task. */
    private static final int MAX_LINES = 5000;

    private final @NonNull SandboxManager sandboxManager;
    private final @NonNull ConcurrentHashMap<String, ManagedTask> tasks = new ConcurrentHashMap<>();
    private final @NonNull AtomicLong idSeq = new AtomicLong();

    /**
     * Per-agent exit notices for tasks that ended since the agent last ran. The UI is pushed
     * TASK_EXITED live, but the agent only executes during an episode, so it is told about these
     * the next time it runs: {@link AgentRunner} drains them into its context at episode start.
     * Keyed by agentId; entries are consumed (cleared) on drain.
     */
    private final @NonNull ConcurrentHashMap<String, java.util.Queue<TaskExitNotice>> exitNotices =
            new ConcurrentHashMap<>();

    private final @NonNull ScheduledExecutorService killer =
            Executors.newSingleThreadScheduledExecutor(
                    r -> {
                        Thread t =
                                Thread.ofPlatform()
                                        .name("bg-task-killer")
                                        .daemon(true)
                                        .unstarted(r);
                        return t;
                    });

    @Autowired
    public BackgroundTaskManager(@NonNull SandboxManager sandboxManager) {
        this.sandboxManager = sandboxManager;
    }

    /**
     * Starts {@code cmd} as a detached background process in {@code cwd} and returns immediately.
     *
     * @param agentId the calling agent (scopes the task; cleanup key)
     * @param cmd the single command to run detached
     * @param cwd the working directory (becomes the sandbox workspace root)
     * @param timeoutSeconds max lifetime; 0 = no cap (run until {@link #stop} or session end),
     *     {@code >0} = auto-kill after this many seconds
     */
    public @NonNull TaskInfo start(
            @NonNull String agentId,
            @NonNull Command cmd,
            @NonNull Path cwd,
            int timeoutSeconds,
            @Nullable UUID sessionId) {
        String taskId = "bg-" + idSeq.incrementAndGet();
        SandboxHandle handle = sandboxManager.substrate().provision(SandboxProfile.defaults(cwd));
        Process process = sandboxManager.substrate().startBackground(handle, cmd, Path.of("."));
        ManagedTask task =
                new ManagedTask(
                        taskId,
                        agentId,
                        process,
                        cmd.executable() + " " + String.join(" ", cmd.args()),
                        cwd.toString(),
                        Instant.now(),
                        process.pid(),
                        sessionId);
        tasks.put(taskId, task);

        // Drain merged stdout+stderr into the ring buffer, then record exit. A virtual thread keeps
        // this cheap; there is one per running task.
        Thread.startVirtualThread(() -> drain(task));

        // Schedule auto-kill when a positive cap is set; cancelled on natural exit / explicit stop.
        if (timeoutSeconds > 0) {
            ScheduledFuture<?> f =
                    killer.schedule(
                            () -> {
                                if (task.alive) {
                                    log.debug(
                                            "Background task {} auto-killed after {}s",
                                            taskId,
                                            timeoutSeconds);
                                    task.cause = ExitCause.AUTO_KILL;
                                    process.destroyForcibly();
                                }
                            },
                            timeoutSeconds,
                            TimeUnit.SECONDS);
            task.killer = f;
        }
        log.info(
                "Background task {} started (agent={}, pid={}, cmd={})",
                taskId,
                agentId,
                task.pid,
                task.command);
        TaskInfo info = task.toInfo();
        notifyStarted(info);
        return info;
    }

    /** Lifecycle listener for task events, routed to the delta broker by an event bridge. */
    public interface TaskListener {
        void onTaskStarted(@NonNull TaskInfo info);

        void onTaskExited(@NonNull TaskInfo info);
    }

    private volatile @Nullable TaskListener taskListener;

    /** Registers the task lifecycle listener (the event bridge); null clears it. */
    public void setTaskListener(@Nullable TaskListener listener) {
        this.taskListener = listener;
    }

    private void notifyStarted(@NonNull TaskInfo info) {
        TaskListener l = taskListener;
        if (l == null) return;
        try {
            l.onTaskStarted(info);
        } catch (RuntimeException e) {
            log.debug("Background task {} start-listener failed", info.taskId(), e);
        }
    }

    private void notifyExited(@NonNull TaskInfo info) {
        TaskListener l = taskListener;
        if (l == null) return;
        try {
            l.onTaskExited(info);
        } catch (RuntimeException e) {
            log.debug("Background task {} exit-listener failed", info.taskId(), e);
        }
    }

    private void drain(@NonNull ManagedTask task) {
        Process process = task.process;
        // Line-buffer RAW BYTES and decode each finished line through SubprocessOutput: console
        // CLIs emit the platform codepage while node/python emit UTF-8, and sniffing needs the
        // whole line's bytes. Splitting on \n/\r bytes is safe — UTF-8 continuation bytes are
        // all >= 0x80, so a line break never falls inside a multi-byte character.
        try (java.io.BufferedInputStream in =
                new java.io.BufferedInputStream(process.getInputStream())) {
            java.io.ByteArrayOutputStream line = new java.io.ByteArrayOutputStream();
            int b;
            boolean skipLf = false;
            while ((b = in.read()) != -1) {
                if (skipLf) {
                    skipLf = false;
                    if (b == '\n') {
                        continue; // the \n half of a \r\n pair
                    }
                }
                if (b == '\n' || b == '\r') {
                    emitLine(task, line);
                    if (b == '\r') {
                        skipLf = true;
                    }
                } else {
                    line.write(b);
                }
            }
            if (line.size() > 0) {
                emitLine(task, line); // final line without a trailing break
            }
        } catch (IOException e) {
            log.debug("Background task {} drain ended: {}", task.taskId, e.getMessage());
        }
        try {
            process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        finishExit(task);
    }

    /** Decodes one raw line (sniffing the encoding) and buffers it ANSI-stripped. */
    private void emitLine(@NonNull ManagedTask task, java.io.@NonNull ByteArrayOutputStream line) {
        // Same decode-seam scrub as the synchronous path: the buffered lines feed both the agent
        // (view_task) and the UI panel, so both get the identical plain text.
        task.buffer.add(AnsiEscapes.strip(SubprocessOutput.decode(line.toByteArray())));
        line.reset();
    }

    private void finishExit(@NonNull ManagedTask task) {
        Process process = task.process;
        task.exitCode = process.exitValue();
        task.finishedAt = Instant.now();
        task.alive = false;
        if (task.killer != null) {
            task.killer.cancel(false);
        }
        log.debug(
                "Background task {} exited (code={}, cause={})",
                task.taskId,
                task.exitCode,
                task.cause);
        notifyExited(task.toInfo());
        // Queue an exit notice so the owning agent is actively told about it on its next turn
        // (the UI already got TASK_EXITED above). exitCode is set just above, so it is non-null.
        int code = task.exitCode != null ? task.exitCode : -1;
        exitNotices
                .computeIfAbsent(
                        task.agentId, k -> new java.util.concurrent.ConcurrentLinkedQueue<>())
                .add(new TaskExitNotice(task.taskId, task.command, code, task.cause));
    }

    /** Status of one task (alive / exitCode / uptime), or {@code empty} if not found. */
    public @NonNull Optional<TaskInfo> status(@NonNull String agentId, @NonNull String taskId) {
        ManagedTask t = owned(agentId, taskId);
        return Optional.ofNullable(t).map(ManagedTask::toInfo);
    }

    /** The last {@code maxLines} of merged output, or {@code empty} if the task is not found. */
    public @NonNull Optional<String> output(
            @NonNull String agentId, @NonNull String taskId, int maxLines) {
        ManagedTask t = owned(agentId, taskId);
        if (t == null) return Optional.empty();
        int n = maxLines > 0 ? maxLines : 50;
        return Optional.of(String.join("\n", t.buffer.tail(n)));
    }

    /**
     * Force-stops a running task, recording WHY it ended ({@code cause}) so the exit notice can
     * tell the agent a user stop from an agent stop from a crash. Returns the post-stop status, or
     * {@code empty} if not found.
     */
    public @NonNull Optional<TaskInfo> stop(
            @NonNull String agentId, @NonNull String taskId, @NonNull ExitCause cause) {
        ManagedTask t = owned(agentId, taskId);
        if (t == null) return Optional.empty();
        if (t.alive) {
            t.cause = cause; // set BEFORE the kill so the drain thread records it
            t.process.destroyForcibly();
            log.info("Background task {} stopped (agent={}, cause={})", taskId, agentId, cause);
        }
        return Optional.of(t.toInfo());
    }

    /**
     * Removes a STOPPED task from the registry (the user-facing cleanup of its card). Running tasks
     * are refused — stop them first — so a remove can never kill a process. Returns the removed
     * task's info, or {@code empty} when not found or still alive.
     */
    public @NonNull Optional<TaskInfo> remove(@NonNull String agentId, @NonNull String taskId) {
        ManagedTask t = owned(agentId, taskId);
        if (t == null || t.alive) {
            return Optional.empty();
        }
        tasks.remove(taskId);
        log.info("Background task {} removed (agent={})", taskId, agentId);
        return Optional.of(t.toInfo());
    }

    /** All tasks for an agent (snapshot). */
    public @NonNull List<TaskInfo> list(@NonNull String agentId) {
        List<TaskInfo> out = new ArrayList<>();
        for (ManagedTask t : tasks.values()) {
            if (agentId.equals(t.agentId)) out.add(t.toInfo());
        }
        return out;
    }

    /**
     * Returns and clears the task-exit notices accumulated for {@code agentId} since it last
     * drained. The agent calls this at episode start so it is actively told about background tasks
     * that ended while it was idle, instead of having to remember to poll {@code view_task}.
     */
    public @NonNull List<TaskExitNotice> drainExitNotices(@NonNull String agentId) {
        java.util.Queue<TaskExitNotice> queue = exitNotices.get(agentId);
        if (queue == null || queue.isEmpty()) {
            return List.of();
        }
        List<TaskExitNotice> out = new ArrayList<>();
        TaskExitNotice notice;
        while ((notice = queue.poll()) != null) {
            out.add(notice);
        }
        return out;
    }

    /** Force-stops every task owned by {@code agentId} (agent removal / session teardown). */
    public void stopAll(@NonNull String agentId) {
        for (ManagedTask t : tasks.values()) {
            if (agentId.equals(t.agentId) && t.alive) {
                t.cause = ExitCause.SHUTDOWN;
                t.process.destroyForcibly();
                log.info(
                        "Background task {} stopped on agent removal (agent={})",
                        t.taskId,
                        agentId);
            }
        }
    }

    /** Spring shutdown hook - kill every still-running task. */
    @jakarta.annotation.PreDestroy
    public void shutdown() {
        for (ManagedTask t : tasks.values()) {
            if (t.alive) {
                t.cause = ExitCause.SHUTDOWN;
                t.process.destroyForcibly();
            }
        }
        killer.shutdownNow();
    }

    /** Returns the task iff it belongs to {@code agentId}; {@code null} otherwise (isolation). */
    private @Nullable ManagedTask owned(@NonNull String agentId, @NonNull String taskId) {
        ManagedTask t = tasks.get(taskId);
        if (t == null || !agentId.equals(t.agentId)) return null;
        return t;
    }

    /**
     * A live background task and its drained output. Mutable fields are written by the drain
     * thread.
     */
    private static final class ManagedTask {
        final @NonNull String taskId;
        final @NonNull String agentId;
        final @NonNull Process process;
        final @NonNull String command;
        final @NonNull String cwd;
        final @NonNull Instant startedAt;
        final long pid;
        final @Nullable UUID sessionId;
        final @NonNull LineBuffer buffer = new LineBuffer(MAX_LINES);
        volatile boolean alive = true;
        volatile @Nullable Integer exitCode = null;
        volatile @Nullable Instant finishedAt = null;
        volatile @Nullable ScheduledFuture<?> killer = null;

        /** Why the task ended; set before any forced kill, read by the drain thread. */
        volatile @NonNull ExitCause cause = ExitCause.NATURAL;

        ManagedTask(
                @NonNull String taskId,
                @NonNull String agentId,
                @NonNull Process process,
                @NonNull String command,
                @NonNull String cwd,
                @NonNull Instant startedAt,
                long pid,
                @Nullable UUID sessionId) {
            this.taskId = taskId;
            this.agentId = agentId;
            this.process = process;
            this.command = command;
            this.cwd = cwd;
            this.startedAt = startedAt;
            this.pid = pid;
            this.sessionId = sessionId;
        }

        @NonNull TaskInfo toInfo() {
            return new TaskInfo(
                    taskId,
                    agentId,
                    command,
                    cwd,
                    startedAt,
                    alive,
                    exitCode,
                    pid,
                    finishedAt,
                    sessionId);
        }
    }

    /** A bounded ring buffer of lines (last {@code maxLines} kept). */
    private static final class LineBuffer {
        private final @NonNull Deque<String> lines = new ArrayDeque<>();
        private final int maxLines;

        LineBuffer(int maxLines) {
            this.maxLines = maxLines;
        }

        synchronized void add(@NonNull String line) {
            lines.addLast(line);
            while (lines.size() > maxLines) lines.removeFirst();
        }

        /** The last {@code n} lines in chronological order. */
        synchronized @NonNull List<String> tail(int n) {
            List<String> out = new ArrayList<>();
            var it = lines.descendingIterator();
            while (it.hasNext() && out.size() < n) out.add(it.next());
            Collections.reverse(out);
            return out;
        }
    }

    /**
     * A task's status, surfaced to the model as JSON via the LLM mapper. {@code exitCode} and
     * {@code finishedAt} are null while the task is still running.
     */
    public record TaskInfo(
            @NonNull String taskId,
            @NonNull String agentId,
            @NonNull String command,
            @NonNull String cwd,
            @NonNull Instant startedAt,
            boolean alive,
            @Nullable Integer exitCode,
            long pid,
            @Nullable Instant finishedAt,
            @Nullable UUID sessionId) {

        /** Convenience: elapsed seconds since start (0 if somehow negative). */
        public long uptimeSeconds() {
            long ms = Duration.between(startedAt, Instant.now()).toSeconds();
            return Math.max(0, ms);
        }
    }

    /**
     * Why a background task ended — the distinction the agent needs between an intentional stop and
     * a crash. Set on the task BEFORE any forced kill; a task that exits on its own keeps {@link
     * #NATURAL} and its exit code carries the clean/crash distinction.
     */
    public enum ExitCause {
        /** The process exited on its own (exit code 0 = clean, non-zero = crash). */
        NATURAL,
        /** The agent stopped it via {@code stop_task}. */
        AGENT_STOP,
        /** The user stopped it from the UI (REST DELETE). */
        USER_STOP,
        /** The lifetime timeout cap fired. */
        AUTO_KILL,
        /** Server shutdown or agent-removal cleanup. */
        SHUTDOWN
    }

    /**
     * A background task's exit, queued for the owning agent to be told about on its next turn.
     * {@code exitCode} is the process exit code ({@code -1} when force-killed/auto-killed); {@code
     * cause} says WHY it ended (user stop vs agent stop vs timeout vs crash).
     */
    public record TaskExitNotice(
            @NonNull String taskId,
            @NonNull String command,
            int exitCode,
            @NonNull ExitCause cause) {}
}
