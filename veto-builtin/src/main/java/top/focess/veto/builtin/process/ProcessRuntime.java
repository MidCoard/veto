package top.focess.veto.builtin.process;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.tool.ToolDocs;
import top.focess.veto.api.agent.tool.ToolPreparation;
import top.focess.veto.api.plugin.PluginContext;
import top.focess.veto.api.plugin.PluginHost;
import top.focess.veto.api.plugin.contract.JsonValue;
import top.focess.veto.api.plugin.contract.SessionLifecycle;
import top.focess.veto.api.process.ChainMode;
import top.focess.veto.api.process.Command;
import top.focess.veto.api.process.CommandResult;
import top.focess.veto.api.process.ProcessHost;
import top.focess.veto.builtin.process.BackgroundTasks.Scope;

/** Builtin policy and views around host-authorized process effects. */
public final class ProcessRuntime implements SessionLifecycle {
    private final @NonNull PluginContext context;
    private final @NonNull BackgroundTasks tasks;
    private TaskEvents events;

    public ProcessRuntime(@NonNull PluginContext context) {
        this.context = context;
        tasks =
                new BackgroundTasks(
                        () ->
                                context.service(ToolDocs.nonNullClass(ProcessHost.class))
                                        .orElseThrow(
                                                () ->
                                                        new IllegalStateException(
                                                                "Process host unavailable")));
    }

    public void events(@NonNull TaskEvents events) {
        this.events = events;
        tasks.listener(events);
    }

    @Override
    public void onAgentTerminated(
            @NonNull String owner, @NonNull String session, @NonNull String agent) {
        var notifications = events;
        if (notifications != null) notifications.agentClosed(new Scope(owner, session, agent));
        tasks.onAgentTerminated(owner, session, agent);
    }

    @Override
    public void onSessionClosed(@NonNull String owner, @NonNull String session) {
        var notifications = events;
        if (notifications != null) notifications.sessionClosed(owner, session);
        tasks.onSessionClosed(owner, session);
    }

    @Override
    public void onOwnerClosed(@NonNull String owner) {
        var notifications = events;
        if (notifications != null) notifications.ownerClosed(owner);
        tasks.onOwnerClosed(owner);
    }

    public @NonNull BackgroundTasks tasks() {
        return tasks;
    }

    private @NonNull ProcessHost processHost() {
        return context.service(ToolDocs.nonNullClass(ProcessHost.class))
                .orElseThrow(() -> new IllegalStateException("Process host unavailable"));
    }

    private @NonNull Scope scope(@NonNull String tool) {
        return Scope.from(
                context.service(ToolDocs.nonNullClass(PluginHost.class))
                        .orElseThrow(() -> new IllegalStateException("Plugin host unavailable"))
                        .invocation(tool));
    }

    public @NonNull ProcessExecutionCapability execution(@NonNull String tool) {
        return new ProcessExecutionCapability() {
            @Override
            public @NonNull CommandResult run(
                    @NonNull List<Command> commands,
                    @NonNull ChainMode mode,
                    @NonNull Duration timeout,
                    boolean network) {
                scope(tool);
                return processHost().runApproved();
            }

            @Override
            public @NonNull TaskInfo start(
                    @NonNull Command command, int timeoutSeconds, boolean network) {
                scope(tool);
                return tasks.start();
            }

            @Override
            public @NonNull Duration maxRuntime() {
                scope(tool);
                return processHost().maxRuntime();
            }

            @Override
            public void cancel(@NonNull String taskId) {
                tasks.stop(scope(tool), taskId, BackgroundTasks.ExitCause.AGENT_STOP);
            }
        };
    }

    public @NonNull TaskControlCapability control(@NonNull String tool) {
        return new TaskControlCapability() {
            @Override
            public @NonNull List<TaskInfo> list() {
                return tasks.list(scope(tool));
            }

            @Override
            public @NonNull Optional<TaskInfo> status(@NonNull String id) {
                return tasks.status(scope(tool), id);
            }

            @Override
            public @NonNull Optional<TaskInfo> awaitExit(@NonNull String id)
                    throws InterruptedException {
                return tasks.awaitExit(scope(tool), id);
            }

            @Override
            public @NonNull Optional<String> output(@NonNull String id, int lines) {
                return tasks.output(scope(tool), id, lines);
            }

            @Override
            public @NonNull List<@NonNull String> inputFailures(@NonNull String id) {
                return tasks.inputFailures(scope(tool), id);
            }

            @Override
            public @NonNull Optional<TaskInfo> stop(@NonNull String id) {
                return tasks.stop(scope(tool), id, BackgroundTasks.ExitCause.AGENT_STOP);
            }

            @Override
            public @NonNull InputResult queueInput(
                    @NonNull String id, byte @NonNull [] bytes, boolean closeStdin) {
                return tasks.queueInput(scope(tool), id);
            }

            @Override
            public @NonNull ToolPreparation prepareInput(
                    PluginHost.@NonNull Invocation invocation,
                    @NonNull String id,
                    byte @NonNull [] bytes,
                    boolean closeStdin) {
                if (bytes.length > 65536)
                    throw new IllegalArgumentException("Input exceeds 65536 bytes");
                if (bytes.length == 0 && !closeStdin)
                    throw new IllegalArgumentException("Input is empty");
                var target =
                        tasks.target(Scope.from(invocation), id)
                                .orElseThrow(() -> new IllegalArgumentException("Task not found"));
                if (!target.info().alive() || !target.stdinAvailable())
                    throw new IllegalArgumentException("Task stdin unavailable");
                return new ToolPreparation(
                        new ToolPreparation.InputIntent(target.process(), bytes, closeStdin),
                        new JsonValue.ObjectValue(Map.of()));
            }
        };
    }
}
