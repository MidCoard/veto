package top.focess.veto.agent.tool;

import org.jspecify.annotations.NonNull;

/**
 * An in-process tool whose {@link ToolSecurity} annotation declares its effect and screening
 * requirements. {@link ToolSchemaCompiler#compileNative} compiles the bean's parameter record and
 * security metadata into a {@link NativeToolDefinition}.
 *
 * @param <T> the record representing the tool's structured parameters
 */
public sealed interface NativeTool<T> extends CapabilityTool<T>
        permits WorkspaceReadTool,
                WorkspaceWriteTool,
                ProcessExecutionTool,
                TaskControlTool,
                NetworkEgressTool,
                WebDocumentTool {

    @Override
    default @NonNull ToolCapability getCapability() {
        return ToolSchemaCompiler.securityOf(getClass()).capability();
    }

    /** The description explaining when and how the LLM should invoke the tool. */
    default @NonNull String getDescription() {
        return ToolDocs.descriptionOf(getClass());
    }
}
