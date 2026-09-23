package top.focess.veto.agent.capability;

import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.tool.ToolCallContextHolder;
import top.focess.veto.api.agent.capability.LoopControlCapability;
import top.focess.veto.api.agent.tool.ToolCapability;
import top.focess.veto.api.agent.workflow.ResponseRequest;

@Component
public final class LoopControlCapabilityImpl implements LoopControlCapability {

    @Override
    public void submitPlan(ResponseRequest.@NonNull Plan plan) throws Exception {
        CapabilityAccess.require(ToolCapability.LOOP_CONTROL, "submit_plan");
        ToolCallContextHolder.requestResponse(plan);
    }

    @Override
    public void answerWithCitations(ResponseRequest.@NonNull Answer answer) throws Exception {
        CapabilityAccess.require(ToolCapability.LOOP_CONTROL, "answer_with_citations");
        ToolCallContextHolder.requestResponse(answer);
    }
}
