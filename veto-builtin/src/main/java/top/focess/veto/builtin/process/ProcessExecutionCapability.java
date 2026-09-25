package top.focess.veto.builtin.process;

import java.time.Duration;
import java.util.List;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.process.ChainMode;
import top.focess.veto.api.process.Command;
import top.focess.veto.api.process.CommandResult;

/** Host capability for running approved commands, synchronously or in the background. */
public interface ProcessExecutionCapability {
    /**
     * Runs the command chain synchronously within the timeout; {@code network} selects the egress
     * policy.
     */
    @NonNull CommandResult run(
            @NonNull List<Command> commands,
            @NonNull ChainMode mode,
            @NonNull Duration timeout,
            boolean network);

    /** Starts the command as a background task and returns its initial info. */
    @NonNull TaskInfo start(@NonNull Command command, int timeoutSeconds, boolean network);

    /** Returns the longest permitted command runtime. */
    @NonNull Duration maxRuntime();

    /** Cancels the background task with the given id. */
    void cancel(@NonNull String taskId);
}
