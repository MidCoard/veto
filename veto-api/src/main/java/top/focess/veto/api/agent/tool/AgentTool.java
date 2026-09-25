package top.focess.veto.api.agent.tool;

/**
 * A record-authored workflow tool using caller-scoped runtime authority.
 *
 * @param <T> immutable argument value decoded by the host
 */
public interface AgentTool<T> extends CapabilityTool<T> {}
