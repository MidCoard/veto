package top.focess.veto.api.agent.capability;

import java.time.Duration;
import java.util.List;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.process.ChainMode;
import top.focess.veto.api.process.Command;
import top.focess.veto.api.process.CommandResult;
import top.focess.veto.api.process.TaskInfo;

public interface ProcessExecutionCapability extends Capability {
    @NonNull CommandResult run(
            @NonNull List<Command> commands,
            @NonNull ChainMode mode,
            @NonNull Duration timeout,
            boolean network);

    @NonNull TaskInfo start(@NonNull Command command, int timeoutSeconds, boolean network);

    @NonNull Duration maxRuntime();

    void cancel(@NonNull String taskId);
}
