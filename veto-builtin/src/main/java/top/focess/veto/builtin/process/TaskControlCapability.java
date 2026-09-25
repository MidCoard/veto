package top.focess.veto.builtin.process;

import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.tool.ToolPreparation;
import top.focess.veto.api.plugin.PluginHost;

/** Host capability for inspecting and controlling background tasks. */
public interface TaskControlCapability {
    /** Prepares a host-authorized stdin write; unsupported by default. */
    default @NonNull ToolPreparation prepareInput(
            PluginHost.@NonNull Invocation invocation,
            @NonNull String taskId,
            byte @NonNull [] bytes,
            boolean closeStdin) {
        throw new IllegalStateException("Task preparation unavailable");
    }

    /** Returns the tasks visible to the calling agent. */
    @NonNull List<TaskInfo> list();

    /** Returns current info for the task; empty when unknown. */
    @NonNull Optional<TaskInfo> status(@NonNull String taskId);

    /** Blocks until the task exits; empty when unknown. */
    @NonNull Optional<TaskInfo> awaitExit(@NonNull String taskId) throws InterruptedException;

    /** Returns the last {@code lines} lines of task output; empty when unknown. */
    @NonNull Optional<String> output(@NonNull String taskId, int lines);

    /** Returns recorded stdin write failures for the task. */
    @NonNull List<@NonNull String> inputFailures(@NonNull String taskId);

    /** Requests task termination; empty when unknown. */
    @NonNull Optional<TaskInfo> stop(@NonNull String taskId);

    /** Queues bytes for the task stdin, optionally closing it afterwards. */
    @NonNull InputResult queueInput(
            @NonNull String taskId, byte @NonNull [] bytes, boolean closeStdin);
}
