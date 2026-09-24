package top.focess.veto.agent.tool;

import java.util.List;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.tool.ToolResult;
import top.focess.veto.api.llm.ToolCall;
import top.focess.veto.api.plugin.PluginHost;

/**
 * The tool engine — manages server registrations, schema discovery, and tool dispatching. The loop
 * calls the methods below.
 *
 * <p>Server registration and MCP transport setup are implementation details absent from this shared
 * interface. There is intentionally no dispatch-by-name/raw-map shortcut: every execution carries
 * the exact resolved definition screened by the caller. {@code resolveDefinition} is required by
 * the loop for that screen/execute pairing.
 */
public interface ToolEngine {

    /** Queries all registered servers to compile a whitelisted tools list for the agent. */
    @NonNull List<ToolDefinition> getActiveTools(Set<String> whitelist);

    /** Resolves a tool name to its typed {@link ToolDefinition} (native / remote / agent). */
    @SuppressWarnings(
            "NullableProblems") // JSpecify and Checker defaults disagree on this override contract.
    ToolDefinition resolveDefinition(@NonNull String toolName);

    default PreparedInvocation prepare(
            @NonNull ToolCall call,
            @NonNull ToolDefinition definition,
            PluginHost.@NonNull Invocation invocation) {
        return null;
    }

    /** Executes a tool call, dispatching by the resolved definition's flavour. */
    @NonNull ToolResult execute(@NonNull ToolCall call, @NonNull ToolDefinition def);
}
