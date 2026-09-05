package top.focess.veto.agent.capability;

import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.tool.builtin.ThinkTool;

/** Operations restricted to the current authorized call and caller. */
public sealed interface LoopControlCapability extends Capability permits LoopControlCapabilityImpl {
    @NonNull String continueLoop(ThinkTool.@NonNull Args args) throws Exception;
}
