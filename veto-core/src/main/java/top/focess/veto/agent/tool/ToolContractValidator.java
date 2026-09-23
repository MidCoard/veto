package top.focess.veto.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import java.lang.reflect.Modifier;
import java.util.stream.Stream;
import org.jspecify.annotations.NonNull;
import org.springframework.aop.support.AopUtils;
import top.focess.veto.agent.capability.*;

/**
 * Validates that tool flavour, capability, danger, and parameter hints describe one coherent tool.
 */
public final class ToolContractValidator {

    private ToolContractValidator() {}

    public static void validate(@NonNull ToolDefinition definition) {
        validateResultFormats(definition);
        if (definition instanceof LocalToolDefinition) {
            validateDocumentation(definition);
            validateExamples(definition);
        }
        switch (definition) {
            case NativeToolDefinition nativeDefinition -> {
                if (nativeDefinition.provenance() != null) {
                    validatePluginNative(nativeDefinition);
                } else {
                    validateNative(nativeDefinition);
                }
            }
            case AgentToolDefinition agentDefinition -> validateAgent(agentDefinition);
            case RemoteToolDefinition ignored -> {
                // Remote definitions carry no compile-time security annotations. An MCP tool
                // hard-codes REMOTE_UNKNOWN/ELEVATED; a script descriptor is validated before
                // activation and its effect remains unknown.
            }
        }
    }

    /**
     * Validates an in-process JAR plugin tool against its definition. The handler is a portable
     * {@link CapabilityTool}, not one of the sealed native/agent boundary interfaces, so this
     * checks definition coherence (capability and argument record) and the shared documentation,
     * example, and result-format contracts — but not the native field-restriction rule, which
     * assumes a caller-scoped capability injection that plugin code does not use. The PRIVILEGED
     * effect keeps every call behind approval-level Gateway screening.
     */
    public static void validatePluginHandler(
            @NonNull CapabilityTool<?> tool, @NonNull NativeToolDefinition definition) {
        require(
                definition,
                tool.getCapability() == definition.capability(),
                "handler capability does not match definition");
        require(
                definition,
                tool.getArgsClass().equals(definition.argsClass()),
                "handler argument record does not match definition");
        validate(definition);
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

    /**
     * Argument and result examples form positionally aligned pairs: {@code returnExamples[i]} shows
     * the success result of the call in {@code examples[i]}. Tools with arguments declare three to
     * five pairs, each example teaching a distinct usage; a no-argument tool declares exactly one
     * empty-call pair.
     */
    private static void validateExamples(@NonNull ToolDefinition definition) {
        int examples = definition.examples().size();
        require(
                definition,
                examples == definition.returnExamples().size(),
                "examples and returnExamples must correspond one-to-one: returnExamples[i] is the"
                        + " success result of the call in examples[i]");
        JsonNode properties = definition.inputSchema().path("properties");
        boolean noArgs = !properties.isObject() || properties.isEmpty();
        if (noArgs) {
            require(
                    definition,
                    examples == 1,
                    "a no-argument tool declares exactly one empty-call example and one matching"
                            + " return example");
        } else {
            require(
                    definition,
                    examples >= 3 && examples <= 5,
                    "declare three to five argument examples, each paired with its positionally"
                            + " matching return example");
        }
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
            case SKILL_READ,
                    MEMORY_READ,
                    MEMORY_WRITE,
                    LOOP_CONTROL,
                    DELEGATION,
                    GROUP_CONTROL,
                    MONITOR_CONTROL,
                    USER_INTERACTION,
                    AGENT_CONTROL,
                    PRIVILEGED,
                    REMOTE_UNKNOWN ->
                    throw invalid(
                            definition,
                            "native tool uses an agent/plugin/remote-only capability: "
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
                    PRIVILEGED,
                    REMOTE_UNKNOWN ->
                    throw invalid(
                            definition,
                            "agent tool uses a native/plugin/remote execution capability: "
                                    + definition.capability());
        }
    }

    private static void validatePluginNative(@NonNull NativeToolDefinition definition) {
        require(
                definition,
                definition.capability() == ToolCapability.PRIVILEGED,
                "an in-process plugin tool crosses the host trust boundary and must declare"
                        + " PRIVILEGED");
        boolean namesExternalResource =
                definition.paramHints().values().stream()
                        .anyMatch(
                                category ->
                                        category == ParamCategory.FILESYSTEM_PATH
                                                || category == ParamCategory.SHELL_COMMAND
                                                || category == ParamCategory.URL);
        require(
                definition,
                !namesExternalResource,
                "a PRIVILEGED plugin tool must not accept host path, command, or URL arguments");
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
