package top.focess.veto.agent.capability;

import com.fasterxml.jackson.databind.JsonNode;
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.loop.ResponseRequest;

/** Operations restricted to the current authorized call and caller. */
public sealed interface LoopControlCapability extends Capability permits LoopControlCapabilityImpl {
    void submitPlan(@NonNull JsonNode actions) throws Exception;

    void answerWithCitations(ResponseRequest.@NonNull Answer answer) throws Exception;
}
