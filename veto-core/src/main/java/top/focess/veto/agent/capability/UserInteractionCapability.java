package top.focess.veto.agent.capability;

import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.tool.builtin.AskUserTool;

/** Operations restricted to the current authorized call and caller. */
public sealed interface UserInteractionCapability extends Capability
        permits UserInteractionCapabilityImpl {
    @NonNull String ask(AskUserTool.@NonNull Args args) throws Exception;
}
