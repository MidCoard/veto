package top.focess.veto.api.agent.tool;

/**
 * A record-authored workflow tool using caller-scoped runtime authority.
 *
 * @param <T> immutable argument value decoded by the host
 */
public abstract class AgentTool<T> extends Tool implements CapabilityTool<T> {
    /** Constructs an agent tool. */
    protected AgentTool() {}
}
