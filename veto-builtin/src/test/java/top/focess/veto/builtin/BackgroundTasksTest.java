package top.focess.veto.builtin;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import top.focess.veto.api.plugin.PluginHost;
import top.focess.veto.api.process.Command;
import top.focess.veto.api.process.CommandResult;
import top.focess.veto.api.process.ProcessHost;
import top.focess.veto.builtin.process.BackgroundTasks;

@Timeout(10)
class BackgroundTasksTest {
    @Test
    void preservesLiveEntriesWhenStopAndShutdownCannotConfirmExit() throws Exception {
        var process = new Running("");
        var host = new Host(process);
        var tasks = new BackgroundTasks(() -> host);
        var info = tasks.start();
        var scope = BackgroundTasks.Scope.from(process.invocation());
        process.refuseClose = true;
        assertThrows(
                IllegalStateException.class,
                () -> tasks.stopOrRemove(scope, info.taskId(), process.id()));
        assertTrue(tasks.exact(scope, info.taskId(), process.id()).alive());
        assertThrows(IllegalStateException.class, tasks::close);
        assertEquals(1, tasks.list(scope).size());
        assertThrows(IllegalStateException.class, tasks::start);
        process.refuseClose = false;
        tasks.close();
        assertTrue(tasks.list(scope).isEmpty());
    }

    @Test
    void scopesAndInstancesAreExactAndNotificationFailureDoesNotLoseOutput() throws Exception {
        var process = new Running("hello\nworld\n");
        var tasks = new BackgroundTasks(() -> new Host(process));
        tasks.listener(
                (scope, info, cause, change) -> {
                    throw new IllegalStateException("offline");
                });
        var info = tasks.start();
        var scope = BackgroundTasks.Scope.from(process.invocation());
        assertTrue(
                tasks.list(new BackgroundTasks.Scope("other", scope.session(), scope.agent()))
                        .isEmpty());
        assertThrows(
                IllegalArgumentException.class,
                () -> tasks.stopOrRemove(scope, info.taskId(), UUID.randomUUID()));
        process.close();
        assertTrue(tasks.awaitExit(scope, info.taskId()).isPresent());
        assertEquals("hello\nworld", tasks.output(scope, info.taskId(), 20).orElseThrow());
        assertEquals("removed", tasks.stopOrRemove(scope, info.taskId(), process.id()).status());
        tasks.close();
    }

    @Test
    void inputFailureDoesNotExposeExceptionMessageOrSubmittedContent() throws Exception {
        var process = new Running("");
        var host = new Host(process);
        var tasks = new BackgroundTasks(() -> host);
        var info = tasks.start();
        var scope = BackgroundTasks.Scope.from(process.invocation());
        try {
            assertTrue(tasks.queueInput(scope, info.taskId()).queued());
            assertTrue(host.wrote.await(2, TimeUnit.SECONDS));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (tasks.inputFailures(scope, info.taskId()).isEmpty()
                    && System.nanoTime() < deadline) Thread.onSpinWait();
            assertEquals(
                    List.of("Approved stdin delivery failed; the task input stream is closed."),
                    tasks.inputFailures(scope, info.taskId()));
        } finally {
            tasks.close();
        }
    }

    @Test
    void retainedOutputIsBoundedAndAllRetainedTextCanBePaged() throws Exception {
        var lines = new StringBuilder();
        for (int i = 0; i < 5003; i++) lines.append("line-").append(i).append('\n');
        var process = new Running(lines.toString());
        var tasks = new BackgroundTasks(() -> new Host(process));
        var info = tasks.start();
        var scope = BackgroundTasks.Scope.from(process.invocation());
        process.close();
        tasks.awaitExit(scope, info.taskId());
        var all = new StringBuilder();
        int offset = 0;
        while (true) {
            var page = tasks.outputPage(scope, info.taskId(), process.id(), offset, 97);
            all.append(page.text());
            Integer next = page.nextOffset();
            if (next == null) break;
            assertTrue(next > offset);
            offset = next;
        }
        assertEquals(tasks.output(scope, info.taskId(), 5000).orElseThrow(), all.toString());
        assertTrue(all.toString().startsWith("line-3\n"));
        assertTrue(all.toString().endsWith("line-5002"));
        tasks.close();
    }

    @Test
    void waitingIsInterruptibleWithoutStoppingDetachedProcess() throws Exception {
        var process = new Running("");
        var tasks = new BackgroundTasks(() -> new Host(process));
        var info = tasks.start();
        var scope = BackgroundTasks.Scope.from(process.invocation());
        var wait = new FutureTask<>(() -> tasks.awaitExit(scope, info.taskId()));
        Thread thread = Thread.startVirtualThread(wait);
        try {
            assertThrows(TimeoutException.class, () -> wait.get(50, TimeUnit.MILLISECONDS));
            thread.interrupt();
            var failure =
                    assertThrows(ExecutionException.class, () -> wait.get(2, TimeUnit.SECONDS));
            var cause = failure.getCause();
            if (cause == null) throw new AssertionError("Missing interruption cause");
            assertInstanceOf(InterruptedException.class, cause);
            assertTrue(process.isAlive());
            assertTrue(tasks.status(scope, info.taskId()).orElseThrow().alive());
        } finally {
            thread.interrupt();
            tasks.close();
            thread.join(2000);
        }
    }

    @Test
    void scopedCleanupStopsEveryOwnedTaskAndReportsCause() throws Exception {
        var first = new Running("");
        var second = new Running("");
        var other = new Running("");
        second.invocation = first.invocation;
        other.invocation =
                new PluginHost.Invocation(
                        "owner", first.invocation.sessionId(), "other", null, "call");
        var queue = new ArrayDeque<Running>(List.of(first, second, other));
        var tasks = new BackgroundTasks(() -> new Host(queue.removeFirst()));
        var causes = new CopyOnWriteArrayList<BackgroundTasks.ExitCause>();
        tasks.listener(
                (scope, info, cause, change) -> {
                    if (change == BackgroundTasks.Change.EXITED) causes.add(cause);
                });
        tasks.start();
        tasks.start();
        var remaining = tasks.start();
        var scope = BackgroundTasks.Scope.from(first.invocation());
        tasks.onAgentTerminated(scope.owner(), scope.session(), scope.agent());
        assertFalse(first.isAlive());
        assertFalse(second.isAlive());
        assertTrue(other.isAlive());
        assertTrue(tasks.list(scope).isEmpty());
        assertEquals(
                List.of(BackgroundTasks.ExitCause.SHUTDOWN, BackgroundTasks.ExitCause.SHUTDOWN),
                causes);
        var otherScope = BackgroundTasks.Scope.from(other.invocation());
        tasks.stop(otherScope, remaining.taskId(), BackgroundTasks.ExitCause.USER_STOP);
        assertEquals(BackgroundTasks.ExitCause.USER_STOP, causes.getLast());
        tasks.close();
    }

    static final class Host implements ProcessHost {
        final @NonNull Running running;
        final @NonNull CountDownLatch wrote = new CountDownLatch(1);

        Host(@NonNull Running running) {
            this.running = running;
        }

        @Override
        public @NonNull CommandResult runApproved() {
            throw new UnsupportedOperationException();
        }

        @Override
        public ProcessHost.@NonNull Running startApproved() {
            return running;
        }

        @Override
        public @NonNull Duration maxRuntime() {
            return Duration.ofMinutes(1);
        }

        @Override
        public ProcessHost.@NonNull InputWrite prepareInput(ProcessHost.@NonNull Running target) {
            assertSame(running, target);
            return new InputWrite() {
                public int byteCount() {
                    return 6;
                }

                public boolean closeStdin() {
                    return false;
                }

                public void write() throws IOException {
                    wrote.countDown();
                    throw new IOException("secret-input at private/path");
                }
            };
        }
    }

    static final class Running implements ProcessHost.Running {
        final @NonNull UUID id = UUID.randomUUID();
        PluginHost.@NonNull Invocation invocation =
                new PluginHost.Invocation(
                        "owner", UUID.randomUUID().toString(), "agent", "request", "call");
        final @NonNull CountDownLatch exited = new CountDownLatch(1);
        final @NonNull String text;
        volatile boolean refuseClose;

        Running(@NonNull String text) {
            this.text = text;
        }

        public @NonNull UUID id() {
            return id;
        }

        public PluginHost.@NonNull Invocation invocation() {
            return invocation;
        }

        public @NonNull Command command() {
            return new Command("program", List.of("arg"));
        }

        public @NonNull String cwd() {
            return "workspace";
        }

        public boolean networkAllowed() {
            return false;
        }

        public @NonNull Duration maxRuntime() {
            return Duration.ofMinutes(1);
        }

        public long pid() {
            return 123;
        }

        public @NonNull InputStream output() {
            return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
        }

        public boolean isAlive() {
            return exited.getCount() != 0;
        }

        public int waitFor() throws InterruptedException {
            exited.await();
            return 0;
        }

        public boolean awaitExit(@NonNull Duration timeout) {
            return !isAlive();
        }

        public int exitValue() {
            if (isAlive()) throw new IllegalStateException();
            return 0;
        }

        public boolean timedOut() {
            return false;
        }

        public void close() {
            if (!refuseClose) exited.countDown();
        }
    }
}
