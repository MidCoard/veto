package top.focess.veto.api.agent.capability;

import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.workflow.ResponseRequest;

/** Operations restricted to the current authorized call and caller. */
public interface LoopControlCapability extends Capability {
    void submitPlan(ResponseRequest.@NonNull Plan plan) throws Exception;

    void answerWithCitations(ResponseRequest.@NonNull Answer answer) throws Exception;
}
