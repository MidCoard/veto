package top.focess.veto.builtin.process;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.plugin.PluginHost;
import top.focess.veto.api.plugin.contract.SessionLifecycle;
import top.focess.veto.api.process.ProcessHost;

/** Volatile builtin task state. The host alone starts and authorizes process effects. */
public final class BackgroundTasks implements SessionLifecycle, AutoCloseable {
    private static final int MAX_LINES = 5000;
    private static final int MAX_LINE_BYTES = 65536;
    private static final int MAX_INPUT_BYTES = 65536;
    private static final int MAX_QUEUED_BYTES = 262144;

    public record Scope(@NonNull String owner, @NonNull String session, @NonNull String agent) {
        public static @NonNull Scope from(PluginHost.@NonNull Invocation invocation) {
            return new Scope(invocation.owner(), invocation.sessionId(), invocation.agentId());
        }
    }

    public enum ExitCause {
        NATURAL,
        AGENT_STOP,
        USER_STOP,
        AUTO_KILL,
        SHUTDOWN
    }

    public enum Change {
        STARTED,
        EXITED,
        REMOVED
    }

    public interface Listener {
        void changed(
                @NonNull Scope scope,
                @NonNull TaskInfo info,
                @NonNull ExitCause cause,
                @NonNull Change change);
    }

    public record Target(
            @NonNull TaskInfo info, ProcessHost.@NonNull Running process, boolean stdinAvailable) {}

    public record Action(@NonNull String status, @NonNull TaskInfo task) {}

    public record TextPage(@NonNull String text, int total, Integer nextOffset) {}

    private final @NonNull Supplier<@NonNull ProcessHost> host;
    private final @NonNull ConcurrentHashMap<String, Task> tasks = new ConcurrentHashMap<>();
    private final @NonNull AtomicLong ids = new AtomicLong();
    private volatile Listener listener;
    private boolean closed;

    public BackgroundTasks(@NonNull Supplier<@NonNull ProcessHost> host) {
        this.host = host;
    }

    public void listener(@NonNull Listener listener) {
        this.listener = listener;
    }

    public synchronized @NonNull TaskInfo start() {
        if (closed) throw new IllegalStateException("Process tasks are closed");
        var process = host.get().startApproved();
        var scope = Scope.from(process.invocation());
        var task = new Task("bg-" + ids.incrementAndGet(), scope, process);
        tasks.put(task.id, task);
        changed(task, Change.STARTED);
        Thread.startVirtualThread(() -> drain(task));
        return task.info();
    }

    public @NonNull List<TaskInfo> list(@NonNull Scope scope) {
        return tasks.values().stream()
                .filter(task -> task.scope.equals(scope))
                .map(Task::info)
                .sorted(
                        Comparator.comparing(TaskInfo::alive)
                                .reversed()
                                .thenComparing(TaskInfo::startedAt)
                                .thenComparing(TaskInfo::taskId))
                .toList();
    }

    public @NonNull Optional<Target> target(@NonNull Scope scope, @NonNull String id) {
        Task task = owned(scope, id);
        if (task == null) return Optional.empty();
        synchronized (task.inputLock) {
            return Optional.of(
                    new Target(task.info(), task.process, !task.stdinClosed && !task.closeQueued));
        }
    }

    public @NonNull Optional<TaskInfo> status(@NonNull Scope scope, @NonNull String id) {
        return Optional.ofNullable(owned(scope, id)).map(Task::info);
    }

    public @NonNull Optional<TaskInfo> awaitExit(@NonNull Scope scope, @NonNull String id)
            throws InterruptedException {
        Task task = owned(scope, id);
        if (task == null) return Optional.empty();
        task.drained.await();
        if (task.alive) throw new IllegalStateException("Task termination could not be confirmed");
        return Optional.of(task.info());
    }

    public @NonNull Optional<String> output(@NonNull Scope scope, @NonNull String id, int lines) {
        Task task = owned(scope, id);
        if (task == null) return Optional.empty();
        synchronized (task.lines) {
            return Optional.of(
                    String.join(
                            "\n",
                            task.lines.stream()
                                    .skip(Math.max(0, task.lines.size() - Math.max(1, lines)))
                                    .toList()));
        }
    }

    public @NonNull TextPage outputPage(
            @NonNull Scope scope,
            @NonNull String id,
            @NonNull UUID instance,
            int offset,
            int limit) {
        Task task = require(scope, id, instance);
        synchronized (task.lines) {
            int total =
                    task.lines.stream().mapToInt(String::length).sum()
                            + Math.max(0, task.lines.size() - 1);
            int start = Math.min(Math.max(0, offset), total);
            int end = Math.min(total, start + Math.min(8192, Math.max(2, limit)));
            var text = new StringBuilder();
            int position = 0;
            for (String line : task.lines) {
                if (position > 0) {
                    if (position - 1 >= start && position - 1 < end) text.append('\n');
                }
                int from = Math.max(0, start - position),
                        to = Math.min(line.length(), end - position);
                if (to > from) text.append(line, from, to);
                position += line.length() + 1;
                if (position > end) break;
            }
            if (!text.isEmpty()
                    && end < total
                    && Character.isHighSurrogate(text.charAt(text.length() - 1))) {
                text.setLength(text.length() - 1);
                end--;
            }
            return new TextPage(text.toString(), total, end < total ? end : null);
        }
    }

    public @NonNull List<@NonNull String> inputFailures(@NonNull Scope scope, @NonNull String id) {
        Task task = owned(scope, id);
        if (task == null) return List.of();
        synchronized (task.inputLock) {
            return List.copyOf(task.failures);
        }
    }

    public @NonNull InputResult queueInput(@NonNull Scope scope, @NonNull String id) {
        Task task = owned(scope, id);
        if (task == null) return InputResult.failure(InputStatus.TASK_NOT_FOUND);
        synchronized (task.inputLock) {
            if (!task.alive) return InputResult.failure(InputStatus.TASK_NOT_RUNNING);
            if (task.stdinClosed || task.closeQueued)
                return InputResult.failure(InputStatus.STDIN_CLOSED);
            var write = host.get().prepareInput(task.process);
            int bytes = write.byteCount();
            if (bytes < 0 || bytes > MAX_INPUT_BYTES)
                return InputResult.failure(InputStatus.INPUT_TOO_LARGE);
            if (task.queuedBytes + bytes > MAX_QUEUED_BYTES)
                return InputResult.failure(InputStatus.INPUT_QUEUE_FULL);
            task.input.addLast(write);
            task.queuedBytes += bytes;
            task.closeQueued |= write.closeStdin();
            if (!task.writing) {
                task.writing = true;
                Thread.startVirtualThread(() -> writeInput(task));
            }
            return new InputResult(InputStatus.QUEUED, bytes, write.closeStdin());
        }
    }

    public @NonNull Optional<TaskInfo> stop(
            @NonNull Scope scope, @NonNull String id, @NonNull ExitCause cause) {
        Task task = owned(scope, id);
        if (task == null) return Optional.empty();
        stop(task, cause);
        return Optional.of(task.info());
    }

    public @NonNull Action stopOrRemove(
            @NonNull Scope scope, @NonNull String id, @NonNull UUID instance) {
        Task task = require(scope, id, instance);
        if (task.alive) {
            stop(task, ExitCause.USER_STOP);
            return new Action("stopped", task.info());
        }
        if (!tasks.remove(id, task))
            throw new IllegalArgumentException("Task is no longer available");
        changed(task, Change.REMOVED);
        return new Action("removed", task.info());
    }

    public @NonNull TaskInfo exact(
            @NonNull Scope scope, @NonNull String id, @NonNull UUID instance) {
        return require(scope, id, instance).info();
    }

    private Task owned(@NonNull Scope scope, @NonNull String id) {
        Task task = tasks.get(id);
        return task != null && task.scope.equals(scope) ? task : null;
    }

    private @NonNull Task require(
            @NonNull Scope scope, @NonNull String id, @NonNull UUID instance) {
        Task task = owned(scope, id);
        if (task == null || !task.process.id().equals(instance))
            throw new IllegalArgumentException("Task is no longer available");
        return task;
    }

    private void drain(@NonNull Task task) {
        try (var input = new BufferedInputStream(task.process.output())) {
            var line = new ByteArrayOutputStream();
            boolean skipLf = false, truncated = false;
            int value;
            while ((value = input.read()) != -1) {
                if (skipLf) {
                    skipLf = false;
                    if (value == '\n') continue;
                }
                if (value == '\r' || value == '\n') {
                    emit(task, line, truncated);
                    truncated = false;
                    skipLf = value == '\r';
                } else if (line.size() < MAX_LINE_BYTES) line.write(value);
                else truncated = true;
            }
            if (line.size() != 0) emit(task, line, truncated);
        } catch (IOException ignored) {
            // Pipe closure is not evidence of process termination; confirm below.
        } finally {
            try {
                task.process.waitFor();
                finish(task);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                task.drained.countDown();
            }
        }
    }

    private void emit(@NonNull Task task, @NonNull ByteArrayOutputStream bytes, boolean truncated) {
        String line = AnsiEscapes.strip(SubprocessOutput.decode(bytes.toByteArray()));
        if (truncated) line += " [line truncated at " + MAX_LINE_BYTES + " bytes]";
        synchronized (task.lines) {
            task.lines.addLast(line);
            while (task.lines.size() > MAX_LINES) task.lines.removeFirst();
        }
        bytes.reset();
    }

    private void writeInput(@NonNull Task task) {
        while (true) {
            ProcessHost.InputWrite write;
            synchronized (task.inputLock) {
                write = task.input.pollFirst();
                if (write == null) {
                    task.writing = false;
                    return;
                }
                task.queuedBytes -= write.byteCount();
            }
            try {
                write.write();
                if (write.closeStdin())
                    synchronized (task.inputLock) {
                        task.stdinClosed = true;
                    }
            } catch (IOException | RuntimeException error) {
                synchronized (task.inputLock) {
                    task.stdinClosed = true;
                    task.input.clear();
                    task.queuedBytes = 0;
                    task.writing = false;
                    task.failures.addLast(
                            "Approved stdin delivery failed; the task input stream is closed.");
                    while (task.failures.size() > 20) task.failures.removeFirst();
                }
                return;
            }
        }
    }

    // Task instances are registry-owned; the parameter is the stable shared exit-state monitor.
    @SuppressWarnings("SynchronizationOnLocalVariableOrMethodParameter")
    private void finish(@NonNull Task task) {
        synchronized (task) {
            if (!task.alive || task.process.isAlive()) return;
            task.exitCode = task.process.exitValue();
            task.finished = Instant.now();
            task.alive = false;
            if (task.process.timedOut()) task.cause = ExitCause.AUTO_KILL;
            changed(task, Change.EXITED);
        }
    }

    private void stop(@NonNull Task task, @NonNull ExitCause cause) {
        if (!task.alive) return;
        task.cause = cause;
        task.process.close();
        try {
            if (!task.process.awaitExit(Duration.ofSeconds(2)))
                throw new IllegalStateException("Process termination still pending");
            finish(task);
            if (task.alive) throw new IllegalStateException("Process termination still pending");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Interrupted while awaiting process termination", interrupted);
        }
    }

    private void changed(@NonNull Task task, @NonNull Change change) {
        var target = listener;
        if (target != null) {
            try {
                target.changed(task.scope, task.info(), task.cause, change);
            } catch (RuntimeException ignored) {
                // Notification failure must not abandon a live process or its output drainer.
            }
        }
    }

    private void stopMatching(@NonNull Predicate<Task> matching) {
        RuntimeException failure = null;
        for (Task task : List.copyOf(tasks.values()))
            if (matching.test(task)) {
                try {
                    stop(task, ExitCause.SHUTDOWN);
                    tasks.remove(task.id, task);
                } catch (RuntimeException error) {
                    if (failure == null) failure = error;
                    else failure.addSuppressed(error);
                }
            }
        if (failure != null) throw failure;
    }

    @Override
    public void onAgentTerminated(
            @NonNull String owner, @NonNull String session, @NonNull String agent) {
        var scope = new Scope(owner, session, agent);
        stopMatching(task -> task.scope.equals(scope));
    }

    @Override
    public void onSessionClosed(@NonNull String owner, @NonNull String session) {
        stopMatching(
                task -> task.scope.owner().equals(owner) && task.scope.session().equals(session));
    }

    @Override
    public void onOwnerClosed(@NonNull String owner) {
        stopMatching(task -> task.scope.owner().equals(owner));
    }

    @Override
    public synchronized void close() {
        closed = true;
        stopMatching(task -> true);
    }

    private static final class Task {
        final @NonNull String id;
        final @NonNull Scope scope;
        final ProcessHost.@NonNull Running process;
        final @NonNull Instant started = Instant.now();
        final @NonNull ArrayDeque<String> lines = new ArrayDeque<>();
        final @NonNull Object inputLock = new Object();
        final @NonNull ArrayDeque<ProcessHost.InputWrite> input = new ArrayDeque<>();
        final @NonNull ArrayDeque<String> failures = new ArrayDeque<>();
        final @NonNull CountDownLatch drained = new CountDownLatch(1);
        volatile boolean alive = true;
        volatile Integer exitCode;
        volatile Instant finished;
        volatile @NonNull ExitCause cause = ExitCause.NATURAL;
        int queuedBytes;
        boolean writing, closeQueued, stdinClosed;

        Task(@NonNull String id, @NonNull Scope scope, ProcessHost.@NonNull Running process) {
            this.id = id;
            this.scope = scope;
            this.process = process;
        }

        @NonNull TaskInfo info() {
            var command = process.command();
            return new TaskInfo(
                    id,
                    scope.agent(),
                    command.executable() + " " + String.join(" ", command.args()),
                    process.cwd(),
                    started,
                    alive,
                    exitCode,
                    process.pid(),
                    finished,
                    UUID.fromString(scope.session()),
                    process.id(),
                    process.invocation().requestId());
        }
    }
}
