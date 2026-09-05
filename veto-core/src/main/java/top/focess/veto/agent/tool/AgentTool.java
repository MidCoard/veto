package top.focess.veto.agent.tool;

/**
 * An in-process agent-runtime tool with an explicitly declared effect capability. {@link
 * ToolEngineImpl} registers its parameter record and capability as an {@link AgentToolDefinition}.
 * Execution requires the current caller's authorization; resource ownership is checked by the
 * corresponding runtime service.
 *
 * @param <T> the record representing the tool's structured parameters
 */
public sealed interface AgentTool<T> extends CapabilityTool<T>
        permits MemoryReadTool,
                MemoryWriteTool,
                DelegationTool,
                GroupControlTool,
                LoopControlTool,
                SkillReadTool,
                UserInteractionTool {}
