package top.focess.veto.agent.capability;

/** Operations restricted to the current authorized call and caller. */
public sealed interface LoopControlCapability extends Capability permits LoopControlCapabilityImpl {
    void submitPlan(
            com.fasterxml.jackson.databind.@org.jspecify.annotations.NonNull JsonNode actions)
            throws Exception;

    void answerWithCitations(
            top.focess.veto.agent.loop.ResponseRequest.@org.jspecify.annotations.NonNull Answer
                    answer)
            throws Exception;
}
