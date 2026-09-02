package top.focess.veto.agent.identity;

/**
 * The agent's operational role. The role scopes the agent's tool set (resolved upstream into {@link
 * AgentPersona#whitelistedTools()}) and selects the role-specific instructions the {@code
 * PromptCompiler} injects into the system prompt.
 *
 * <ul>
 *   <li>{@link #STANDALONE} - a directly-addressable agent operating on the workspace. May delegate
 *       a decomposable task by calling {@code create_group}.
 *   <li>{@link #LEADER} - the strategist of a delegation group: plans (authors the DAG), dispatches
 *       to Mates, relays feedback, synthesizes the result. Never executes task nodes.
 *   <li>{@link #MATE} - a worker in a delegation group: executes dispatched nodes and reports to
 *       the Leader. Does not delegate further.
 * </ul>
 */
public enum Role {
    STANDALONE,
    LEADER,
    MATE
}
