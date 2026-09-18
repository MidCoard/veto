package top.focess.veto.agent.capability;

import com.fasterxml.jackson.databind.JsonNode;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.loop.ResponseRequest;
import top.focess.veto.agent.tool.ToolCallContextHolder;
import top.focess.veto.agent.tool.ToolCapability;

@Component
public final class LoopControlCapabilityImpl implements LoopControlCapability {

    @Override
    public void submitPlan(@NonNull JsonNode actions) throws Exception {
        CapabilityAccess.require(ToolCapability.LOOP_CONTROL, "submit_plan");
        ToolCallContextHolder.requestResponse(new ResponseRequest.Plan(actions));
    }

    @Override
    public void answerWithCitations(ResponseRequest.@NonNull Answer answer) throws Exception {
        CapabilityAccess.require(ToolCapability.LOOP_CONTROL, "answer_with_citations");
        ToolCallContextHolder.requestResponse(answer);
    }
}
