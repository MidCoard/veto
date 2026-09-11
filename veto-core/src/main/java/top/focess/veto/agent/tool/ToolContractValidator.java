package top.focess.veto.agent.tool;

import java.lang.reflect.Modifier;
import java.util.stream.Stream;
import org.jspecify.annotations.NonNull;
import org.springframework.aop.support.AopUtils;
import top.focess.veto.agent.capability.*;
import top.focess.veto.agent.screening.Danger;

/**
 * Validates that tool flavour, capability, danger, and parameter hints describe one coherent tool.
 */
public final class ToolContractValidator {

    private ToolContractValidator() {}

    public static void validate(@NonNull ToolDefinition definition) {
        validateResultFormats(definition);
        if (!(definition instanceof RemoteToolDefinition)) {
            validateDocumentation(definition);
        }
        switch (definition) {
            case NativeToolDefinition nativeDefinition -> validateNative(nativeDefinition);
            case AgentToolDefinition agentDefinition -> validateAgent(agentDefinition);
            case RemoteToolDefinition ignored -> {
                // Remote definitions hard-code REMOTE_UNKNOWN and ELEVATED.
            }
        }
    }

    /**
     * Rejects handlers that bypass their declared effect boundary before they enter the registry.
     */
    public static void validateHandler(
            @NonNull CapabilityTool<?> tool, @NonNull ToolDefinition definition) {
        require(
                definition,
                ToolDocs.toolDocOf(tool.getArgsClass()) == null,
                "ToolDoc belongs on the tool implementation class, not the argument record");
        validate(definition);
        require(
                definition,
                tool.getName().equals(definition.name()),
                "handler name does not match definition");
        require(
                definition,
                tool.getCapability() == definition.capability(),
                "handler capability does not match definition");
        boolean correctBoundary =
                switch (definition.capability()) {
                    case WORKSPACE_READ -> tool instanceof WorkspaceReadTool<?>;
                    case WORKSPACE_WRITE -> tool instanceof WorkspaceWriteTool<?>;
                    case PROCESS_EXECUTION -> tool instanceof ProcessExecutionTool<?>;
                    case TASK_CONTROL -> tool instanceof TaskControlTool<?>;
                    case NETWORK_EGRESS ->
                            tool instanceof NetworkEgressTool<?>
                                    || tool instanceof WebDocumentTool<?>;
                    case MEMORY_READ -> tool instanceof MemoryReadTool<?>;
                    case CREDENTIAL_IMPORT -> tool instanceof CredentialImportTool<?>;
                    case MEMORY_WRITE -> tool instanceof MemoryWriteTool<?>;
                    case DELEGATION -> tool instanceof DelegationTool<?>;
                    case GROUP_CONTROL -> tool instanceof GroupControlTool<?>;
                    case MONITOR_CONTROL -> tool instanceof MonitorTool<?>;
                    case LOOP_CONTROL -> tool instanceof LoopControlTool<?>;
                    case SKILL_READ -> tool instanceof SkillReadTool<?>;
                    case USER_INTERACTION -> tool instanceof UserInteractionTool<?>;
                    default -> false;
                };
        require(
                definition,
                correctBoundary,
                "handler does not implement the declared capability boundary");
        Class<?> capability =
                switch (definition.capability()) {
                    case WORKSPACE_READ -> ToolDocs.nonNullClass(WorkspaceReadCapability.class);
                    case WORKSPACE_WRITE -> ToolDocs.nonNullClass(WorkspaceWriteCapability.class);
                    case PROCESS_EXECUTION ->
                            ToolDocs.nonNullClass(ProcessExecutionCapability.class);
                    case TASK_CONTROL -> ToolDocs.nonNullClass(TaskControlCapability.class);
                    case NETWORK_EGRESS ->
                            tool instanceof WebDocumentTool<?>
                                    ? ToolDocs.nonNullClass(WebDocumentCapability.class)
                                    : ToolDocs.nonNullClass(NetworkEgressCapability.class);
                    case MEMORY_READ -> ToolDocs.nonNullClass(MemoryReadCapability.class);
                    case CREDENTIAL_IMPORT ->
                            ToolDocs.nonNullClass(CredentialImportCapability.class);
                    case MEMORY_WRITE -> ToolDocs.nonNullClass(MemoryWriteCapability.class);
                    case DELEGATION -> ToolDocs.nonNullClass(DelegationCapability.class);
                    case GROUP_CONTROL -> ToolDocs.nonNullClass(GroupControlCapability.class);
                    case MONITOR_CONTROL -> ToolDocs.nonNullClass(MonitorCapability.class);
                    case LOOP_CONTROL -> ToolDocs.nonNullClass(LoopControlCapability.class);
                    case SKILL_READ -> ToolDocs.nonNullClass(SkillReadCapability.class);
                    case USER_INTERACTION -> ToolDocs.nonNullClass(UserInteractionCapability.class);
                    default -> throw invalid(definition, "handler has no restricted capability");
                };
        Class<?> type = AopUtils.getTargetClass(tool);
        for (Class<?> current = type;
                current != null && current != Object.class;
                current = current.getSuperclass()) {
            for (var field : current.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                require(
                        definition,
                        field.getType().isInterface()
                                && capability.isAssignableFrom(field.getType()),
                        "handler holds unrestricted instance dependency '"
                                + field.getName()
                                + "' ("
                                + field.getType().getName()
                                + ")");
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

    private static void validateDocumentation(@NonNull ToolDefinition definition) {
        ToolDocumentation documentation = definition.documentation();
        require(definition, !documentation.behavior().isBlank(), "behavior is required");
        require(definition, !documentation.whenToUse().isBlank(), "whenToUse is required");
        require(definition, !documentation.whenNotToUse().isBlank(), "whenNotToUse is required");
        require(
                definition,
                !documentation.resultContract().isBlank(),
                "resultContract is required");
        require(
                definition,
                !documentation.errorsAndEdgeCases().isBlank(),
                "errorsAndEdgeCases is required");
        require(definition, !documentation.security().isBlank(), "security is required");
        boolean embedsHeading =
                Stream.of(
                                documentation.behavior(),
                                documentation.whenToUse(),
                                documentation.whenNotToUse(),
                                documentation.resultContract(),
                                documentation.errorsAndEdgeCases(),
                                documentation.security())
                        .anyMatch(section -> section.contains("#### "));
        require(
                definition,
                !embedsHeading,
                "documentation fields must not embed Markdown section headings");
    }

    private static void validateNative(@NonNull NativeToolDefinition definition) {
        boolean hasPath = definition.paramHints().containsValue(ParamCategory.FILESYSTEM_PATH);
        boolean hasCommand = definition.paramHints().containsValue(ParamCategory.SHELL_COMMAND);
        switch (definition.capability()) {
            case WORKSPACE_READ ->
                    require(
                            definition,
                            hasPath,
                            "WORKSPACE_READ requires a FILESYSTEM_PATH parameter");
            case WORKSPACE_WRITE ->
                    require(
                            definition,
                            hasPath,
                            "WORKSPACE_WRITE requires a FILESYSTEM_PATH parameter");
            case PROCESS_EXECUTION ->
                    require(
                            definition,
                            !hasPath && hasCommand,
                            "PROCESS_EXECUTION requires SHELL_COMMAND parameters; its working directory comes from the session permit, not a FILESYSTEM_PATH argument");
            case TASK_CONTROL ->
                    require(
                            definition,
                            !hasPath && !hasCommand,
                            "TASK_CONTROL must not accept host path/command arguments");
            case NETWORK_EGRESS -> {
                // URL arguments are optional because some network tools use deployer-fixed hosts.
            }
            case CREDENTIAL_IMPORT ->
                    require(
                            definition,
                            !hasPath
                                    && !hasCommand
                                    && !definition.paramHints().containsValue(ParamCategory.URL)
                                    && definition.defaultDanger().ordinal()
                                            >= Danger.DANGEROUS.ordinal(),
                            "CREDENTIAL_IMPORT requires approval-level danger and no path/command/URL arguments");
            case SKILL_READ,
                    MEMORY_READ,
                    MEMORY_WRITE,
                    LOOP_CONTROL,
                    DELEGATION,
                    GROUP_CONTROL,
                    MONITOR_CONTROL,
                    USER_INTERACTION,
                    AGENT_CONTROL,
                    REMOTE_UNKNOWN ->
                    throw invalid(
                            definition,
                            "native tool uses an agent/remote-only capability: "
                                    + definition.capability());
        }
    }

    private static void validateAgent(@NonNull AgentToolDefinition definition) {
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
            case SKILL_READ,
                    MEMORY_READ,
                    MEMORY_WRITE,
                    LOOP_CONTROL,
                    DELEGATION,
                    GROUP_CONTROL,
                    MONITOR_CONTROL,
                    USER_INTERACTION -> {
                // These capabilities execute through typed, caller-scoped runtime services.
            }
            case AGENT_CONTROL ->
                    throw invalid(
                            definition, "AGENT_CONTROL does not declare a restricted capability");
            case WORKSPACE_READ,
                    WORKSPACE_WRITE,
                    PROCESS_EXECUTION,
                    TASK_CONTROL,
                    NETWORK_EGRESS,
                    CREDENTIAL_IMPORT,
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
