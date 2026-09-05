package top.focess.veto.agent.capability;

/** Operations restricted to the current authorized call and caller. */
public sealed interface LoopControlCapability extends Capability permits LoopControlCapabilityImpl {
    void continueLoop() throws Exception;
}
