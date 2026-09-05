package top.focess.veto.agent.capability;

import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.tool.ToolCapability;
import top.focess.veto.agent.tool.builtin.ThinkTool;

@Component
public final class LoopControlCapabilityImpl implements LoopControlCapability {

    @Override
    public @NonNull String continueLoop(ThinkTool.@NonNull Args args) throws Exception {
        CapabilityAccess.require(ToolCapability.LOOP_CONTROL, "think");

        return "";
    }
}
