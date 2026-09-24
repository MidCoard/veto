package top.focess.veto.builtin;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import top.focess.veto.api.agent.tool.ToolDocs;
import top.focess.veto.api.agent.tool.ToolPreparation;
import top.focess.veto.api.plugin.PluginContext;
import top.focess.veto.api.plugin.PluginHost;
import top.focess.veto.api.plugin.PluginIdentity;
import top.focess.veto.api.process.Command;
import top.focess.veto.api.process.CommandResult;
import top.focess.veto.api.process.ProcessHost;
import top.focess.veto.builtin.process.BackgroundTasks;
import top.focess.veto.builtin.process.ProcessRuntime;
import top.focess.veto.builtin.tools.InputTaskTool;
import top.focess.veto.builtin.tools.RunCommandTool;
import top.focess.veto.builtin.tools.RunTaskTool;
import top.focess.veto.builtin.tools.StopTaskTool;
import top.focess.veto.builtin.tools.ViewTaskTool;

/** API-only feature journey; host admission/containment is tested separately in core. */
@Timeout(10)
class ProcessToolsTest {
    @Test
    void preparedTaskInputAndEofProduceOutputAndPreserveOrigin() throws Exception {
        var host = new Host();
        var runtime =
                new ProcessRuntime(
                        new PluginContext(
                                new PluginIdentity("test", "1.0.0"),
                                Map.of(
                                        ToolDocs.nonNullClass(PluginHost.class),
                                        host,
                                        ToolDocs.nonNullClass(ProcessHost.class),
                                        host)));
        var run = new RunTaskTool(runtime.execution("run_task"));
        var view = new ViewTaskTool(runtime.control("view_task"));
        var input = new InputTaskTool(runtime.control("input_task"));
        var stop = new StopTaskTool(runtime.control("stop_task"));
        var mapper = new ObjectMapper();
        String javaHome = System.getProperty("java.home");
        String executable =
                Path.of(
                                javaHome,
                                "bin",
                                System.getProperty("os.name").startsWith("Windows")
                                        ? "java.exe"
                                        : "java")
                        .toString();
        var source = ToolDocs.nonNullClass(Echo.class).getProtectionDomain().getCodeSource();
        if (source == null) throw new AssertionError("Missing subprocess fixture code source");
        String classpath = Path.of(source.getLocation().toURI()).toString();
        var args =
                new RunTaskTool.Args(
                        List.of(
                                new RunCommandTool.CommandInput(
                                        executable,
                                        List.of("-cp", classpath, Echo.class.getName()))),
                        false,
                        30);
        try {
            host.preparation = run.prepare(args, host.invocation);
            var started = mapper.readTree(run.execute(args));
            assertEquals("started", started.path("status").asText());
            String id = started.path("taskId").asText();
            var write = new InputTaskTool.Args(id, "hello", true, true);
            host.preparation = input.prepare(write, host.invocation);
            var queued = mapper.readTree(input.execute(write));
            assertEquals("queued", queued.path("status").asText());
            assertEquals(6, queued.path("bytes").asInt());
            assertTrue(queued.path("closeQueued").asBoolean());
            var status = mapper.readTree(view.execute(new ViewTaskTool.Args(id, true)));
            assertFalse(status.path("alive").asBoolean());
            assertEquals(0, status.path("exitCode").asInt());
            assertTrue(status.path("recentOutput").asText().contains("got:hello"));
            assertEquals(
                    "original-request",
                    runtime.tasks()
                            .status(BackgroundTasks.Scope.from(host.invocation), id)
                            .orElseThrow()
                            .requestId());
            assertEquals(
                    "already_exited",
                    mapper.readTree(stop.execute(new StopTaskTool.Args(id)))
                            .path("status")
                            .asText());
        } finally {
            runtime.tasks().close();
        }
    }

    @Test
    void multipleBackgroundCommandsAreRejectedDuringPreparation() {
        var tool = new RunTaskTool();
        var args =
                new RunTaskTool.Args(
                        List.of(
                                new RunCommandTool.CommandInput("a", List.of()),
                                new RunCommandTool.CommandInput("b", List.of())),
                        false,
                        0);
        var invocation =
                new PluginHost.Invocation(
                        "owner", UUID.randomUUID().toString(), "agent", null, "call");
        assertThrows(IllegalArgumentException.class, () -> tool.prepare(args, invocation));
    }

    public static final class Echo {
        public static void main(String[] args) throws IOException {
            var reader = new BufferedReader(new InputStreamReader(System.in));
            String line = reader.readLine();
            if (line != null) System.out.println("got:" + line);
            if (reader.read() != -1) throw new IllegalStateException("Expected EOF");
        }
    }

    private static final class Host implements ProcessHost, PluginHost {
        final PluginHost.@NonNull Invocation invocation =
                new PluginHost.Invocation(
                        "owner", UUID.randomUUID().toString(), "agent", "original-request", "call");
        @Nullable ToolPreparation preparation;

        public @NonNull Invocation invocation(@NonNull String tool) {
            return invocation;
        }

        public void wake(@NonNull String owner, @NonNull String session, @NonNull String agent) {}

        public void invalidate(@NonNull String session, @NonNull String resource) {}

        public @NonNull CommandResult runApproved() {
            throw new UnsupportedOperationException();
        }

        public @NonNull Duration maxRuntime() {
            return Duration.ofSeconds(30);
        }

        public ProcessHost.@NonNull Running startApproved() {
            var value = preparation;
            if (value == null || !(value.intent() instanceof ToolPreparation.ProcessIntent intent))
                throw new IllegalStateException();
            Command command = intent.commands().getFirst();
            var argv = new ArrayList<String>();
            argv.add(command.executable());
            argv.addAll(command.args());
            try {
                return new RunningProcess(
                        new ProcessBuilder(argv).redirectErrorStream(true).start(),
                        command,
                        invocation);
            } catch (IOException error) {
                throw new IllegalStateException(error);
            }
        }

        public ProcessHost.@NonNull InputWrite prepareInput(ProcessHost.@NonNull Running process) {
            var value = preparation;
            if (value == null
                    || !(value.intent() instanceof ToolPreparation.InputIntent intent)
                    || intent.process() != process) throw new IllegalStateException();
            var target = (RunningProcess) process;
            byte[] bytes = intent.bytes();
            boolean close = intent.closeStdin();
            return new InputWrite() {
                boolean used;

                public int byteCount() {
                    return bytes.length;
                }

                public boolean closeStdin() {
                    return close;
                }

                public synchronized void write() throws IOException {
                    if (used) throw new IOException("Consumed");
                    used = true;
                    target.process.getOutputStream().write(bytes);
                    target.process.getOutputStream().flush();
                    if (close) target.process.getOutputStream().close();
                }
            };
        }
    }

    private record RunningProcess(
            @NonNull Process process,
            @NonNull Command command,
            PluginHost.@NonNull Invocation invocation,
            @NonNull UUID id)
            implements ProcessHost.Running {
        RunningProcess(
                @NonNull Process process,
                @NonNull Command command,
                PluginHost.@NonNull Invocation invocation) {
            this(process, command, invocation, UUID.randomUUID());
        }

        public @NonNull String cwd() {
            return ".";
        }

        public boolean networkAllowed() {
            return false;
        }

        public @NonNull Duration maxRuntime() {
            return Duration.ofSeconds(30);
        }

        public long pid() {
            return process.pid();
        }

        public @NonNull InputStream output() {
            return process.getInputStream();
        }

        public boolean isAlive() {
            return process.isAlive();
        }

        public int waitFor() throws InterruptedException {
            return process.waitFor();
        }

        public boolean awaitExit(@NonNull Duration timeout) throws InterruptedException {
            return process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        public int exitValue() {
            return process.exitValue();
        }

        public boolean timedOut() {
            return false;
        }

        public void close() {
            process.destroy();
        }
    }
}
