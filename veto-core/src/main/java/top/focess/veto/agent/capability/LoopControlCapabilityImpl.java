package top.focess.veto.agent.capability;

import org.springframework.stereotype.Component;
import top.focess.veto.agent.tool.ToolCapability;

@Component
public final class LoopControlCapabilityImpl implements LoopControlCapability {

    @Override
    public void submitPlan(
            com.fasterxml.jackson.databind.@org.jspecify.annotations.NonNull JsonNode actions)
            throws Exception {
        CapabilityAccess.require(ToolCapability.LOOP_CONTROL, "submit_plan");
        top.focess.veto.agent.tool.ToolCallContextHolder.requestResponse(
                new top.focess.veto.agent.loop.ResponseRequest.Plan(actions));
    }

    @Override
    public void answerWithCitations(
            top.focess.veto.agent.loop.ResponseRequest.@org.jspecify.annotations.NonNull Answer
                    answer)
            throws Exception {
        CapabilityAccess.require(ToolCapability.LOOP_CONTROL, "answer_with_citations");
        top.focess.veto.agent.tool.ToolCallContextHolder.requestResponse(answer);
    }
}
