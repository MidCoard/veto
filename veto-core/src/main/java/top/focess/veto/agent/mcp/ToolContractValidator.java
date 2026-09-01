package top.focess.veto.agent.mcp;

import org.jspecify.annotations.NonNull;

/**
 * Validates that tool flavour, capability, risk, and parameter hints describe one coherent tool.
 */
public final class ToolContractValidator {

    private ToolContractValidator() {}

    public static void validate(@NonNull ToolDefinition definition) {
        validateResultFormats(definition);
        switch (definition) {
            case NativeToolDefinition nativeDefinition -> validateNative(nativeDefinition);
            case AgentToolDefinition agentDefinition -> validateAgent(agentDefinition);
            case RemoteToolDefinition remoteDefinition -> {
                if (remoteDefinition.capability() != ToolCapability.REMOTE_UNKNOWN) {
                    throw invalid(
                            remoteDefinition, "remote MCP tools must default to REMOTE_UNKNOWN");
                }
                if (remoteDefinition.risk() != RiskCategory.NETWORK) {
                    throw invalid(
                            remoteDefinition,
                            "unclassified remote MCP tools must use NETWORK screening risk");
                }
            }
        }
    }

    private static void validateResultFormats(@NonNull ToolDefinition definition) {
        var formats = definition.resultFormats();
        require(definition, !formats.isEmpty(), "at least one result format is required");
        require(
                definition,
                formats.contains(ToolResultFormat.JSON)
                        || formats.contains(ToolResultFormat.PLAINTEXT),
                "at least one successful result format (JSON or PLAINTEXT) is required");
    }

    private static void validateNative(@NonNull NativeToolDefinition definition) {
        boolean hasPath = definition.paramHints().containsValue(ParamCategory.FILESYSTEM_PATH);
        boolean hasCommand = definition.paramHints().containsValue(ParamCategory.SHELL_COMMAND);
        switch (definition.capability()) {
            case WORKSPACE_READ ->
                    require(
                            definition,
                            definition.risk() == RiskCategory.READ_ONLY && hasPath,
                            "WORKSPACE_READ requires READ_ONLY risk and a FILESYSTEM_PATH parameter");
            case WORKSPACE_WRITE ->
                    require(
                            definition,
                            definition.risk() == RiskCategory.FILE_WRITE && hasPath,
                            "WORKSPACE_WRITE requires FILE_WRITE risk and a FILESYSTEM_PATH parameter");
            case PROCESS_EXECUTION ->
                    require(
                            definition,
                            definition.risk() == RiskCategory.SHELL_EXEC && hasPath && hasCommand,
                            "PROCESS_EXECUTION requires SHELL_EXEC risk plus FILESYSTEM_PATH and SHELL_COMMAND parameters");
            case TASK_CONTROL ->
                    require(
                            definition,
                            definition.risk() == RiskCategory.AGENT && !hasPath && !hasCommand,
                            "TASK_CONTROL must be agent-scoped and must not accept host path/command arguments");
            case NETWORK_EGRESS ->
                    require(
                            definition,
                            definition.risk() == RiskCategory.NETWORK,
                            "NETWORK_EGRESS requires NETWORK risk");
            case SKILL_READ, MEMORY_READ, MEMORY_WRITE, AGENT_CONTROL, REMOTE_UNKNOWN ->
                    throw invalid(
                            definition,
                            "native tool uses an agent/remote-only capability: "
                                    + definition.capability());
        }
    }

    private static void validateAgent(@NonNull AgentToolDefinition definition) {
        if (definition.risk() != RiskCategory.AGENT) {
            throw invalid(definition, "agent tools must carry AGENT risk");
        }
        boolean namesExternalResource =
                definition.paramHints().values().stream()
                        .anyMatch(
                                category ->
                                        category == ParamCategory.FILESYSTEM_PATH
                                                || category == ParamCategory.SHELL_COMMAND
                                                || category == ParamCategory.URL);
        if (namesExternalResource) {
            throw invalid(
                    definition,
                    "agent tools may not early-route with filesystem, command, or URL parameters");
        }
        switch (definition.capability()) {
            case SKILL_READ, MEMORY_READ, MEMORY_WRITE, AGENT_CONTROL -> {
                // These capabilities execute through typed, caller-scoped runtime services.
            }
            case WORKSPACE_READ,
                    WORKSPACE_WRITE,
                    PROCESS_EXECUTION,
                    TASK_CONTROL,
                    NETWORK_EGRESS,
                    REMOTE_UNKNOWN ->
                    throw invalid(
                            definition,
                            "agent tool uses a native/remote execution capability: "
                                    + definition.capability());
        }
    }

    private static void require(
            @NonNull ToolDefinition definition, boolean condition, @NonNull String message) {
        if (!condition) {
            throw invalid(definition, message);
        }
    }

    private static @NonNull IllegalArgumentException invalid(
            @NonNull ToolDefinition definition, @NonNull String message) {
        return new IllegalArgumentException(
                "Invalid tool contract for '" + definition.name() + "': " + message);
    }
}
