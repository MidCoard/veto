package top.focess.veto.builtin.process;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.tool.ToolPreparation;
import top.focess.veto.api.event.AgentTerminatedEvent;
import top.focess.veto.api.event.EventHandler;
import top.focess.veto.api.event.Listener;
import top.focess.veto.api.event.OwnerClosedEvent;
import top.focess.veto.api.event.SessionClosedEvent;
import top.focess.veto.api.plugin.PluginContext;
import top.focess.veto.api.plugin.PluginHost;
import top.focess.veto.api.plugin.contract.JsonValue;
import top.focess.veto.api.process.ChainMode;
import top.focess.veto.api.process.Command;
import top.focess.veto.api.process.CommandResult;
import top.focess.veto.api.process.ProcessHost;
import top.focess.veto.builtin.process.BackgroundTasks.Scope;

/** Builtin policy and views around host-authorized process effects. */
public final class ProcessRuntime extends Listener {
    private final @NonNull PluginContext context;
    private final @NonNull BackgroundTasks tasks;
    private final @NonNull TaskEvents events;

    /** Creates the runtime bound to the context-supplied process host. */
    public ProcessRuntime(@NonNull PluginContext context, @NonNull TaskEvents events) {
        this.context = context;
        this.events = events;
        tasks =
                new BackgroundTasks(
                        () ->
                                context.service(ProcessHost.class)
                                        .orElseThrow(
                                                () ->
                                                        new IllegalStateException(
                                                                "Process host unavailable")));
        tasks.listener(events);
    }

    @EventHandler
    public void onAgentTerminated(@NonNull AgentTerminatedEvent event) {
        events.agentClosed(new Scope(event.owner(), event.sessionId(), event.agentId()));
        tasks.onAgentTerminated(event.owner(), event.sessionId(), event.agentId());
    }

    @EventHandler
    public void onSessionClosed(@NonNull SessionClosedEvent event) {
        events.sessionClosed(event.owner(), event.sessionId());
        tasks.onSessionClosed(event.owner(), event.sessionId());
    }

    @EventHandler
    public void onOwnerClosed(@NonNull OwnerClosedEvent event) {
        events.ownerClosed(event.owner());
        tasks.onOwnerClosed(event.owner());
    }

    /** Returns the volatile background-task registry. */
    public @NonNull BackgroundTasks tasks() {
        return tasks;
    }

    private @NonNull ProcessHost processHost() {
        return context.service(ProcessHost.class)
                .orElseThrow(() -> new IllegalStateException("Process host unavailable"));
    }

    private @NonNull Scope scope(@NonNull String tool) {
        return Scope.from(
                context.service(PluginHost.class)
                        .orElseThrow(() -> new IllegalStateException("Plugin host unavailable"))
                        .invocation(tool));
    }

    /** Returns a scope-checked execution capability for the named tool. */
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

    /** Returns a scope-checked task-control capability for the named tool. */
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
