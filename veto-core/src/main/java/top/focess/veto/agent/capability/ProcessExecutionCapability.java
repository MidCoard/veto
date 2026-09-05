package top.focess.veto.agent.capability;

import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.tool.builtin.RunCommandTool;
import top.focess.veto.agent.tool.builtin.RunTaskTool;

public sealed interface ProcessExecutionCapability extends Capability
        permits ProcessExecutionCapabilityImpl {
    @NonNull String runCommand(RunCommandTool.@NonNull Args args);

    @NonNull String runTask(RunTaskTool.@NonNull Args args);
}
