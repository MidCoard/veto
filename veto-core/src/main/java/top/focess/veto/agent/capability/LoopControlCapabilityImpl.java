package top.focess.veto.agent.capability;

import org.springframework.stereotype.Component;
import top.focess.veto.agent.tool.ToolCapability;

@Component
public final class LoopControlCapabilityImpl implements LoopControlCapability {

    @Override
    public void continueLoop() throws Exception {
        CapabilityAccess.require(ToolCapability.LOOP_CONTROL, "think");
    }
}
