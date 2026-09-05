package top.focess.veto.agent.capability;

import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.tool.builtin.InputTaskTool;
import top.focess.veto.agent.tool.builtin.StopTaskTool;
import top.focess.veto.agent.tool.builtin.ViewTaskTool;

public sealed interface TaskControlCapability extends Capability permits TaskControlCapabilityImpl {
    @NonNull String viewTask(ViewTaskTool.@NonNull Args args);

    @NonNull String stopTask(StopTaskTool.@NonNull Args args);

    @NonNull String inputTask(InputTaskTool.@NonNull Args args);
}
