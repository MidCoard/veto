package top.focess.veto.agent.tool;

import org.jspecify.annotations.NonNull;

/**
 * Contract for a native in-process tool. The implementing class (a Java record carrying the tool's
 * structured parameters) must be annotated with {@link ToolSecurity} to declare its capability and
 * default danger, and each parameter record component may carry a {@link SecurityHint} so the
 * Gateway knows how to screen individual arguments.
 *
 * <p>The implementing record is both the parameter container and the tool bean: {@link
 * #getArgsClass} returns the record itself, and {@link #execute(Object)} runs the tool's typed
 * logic. The {@link ToolSchemaCompiler#compileNative} factory reflects over the bean to derive its
 * {@link NativeToolDefinition} (schema + security hints) without ever instantiating it — the bean
 * instance is Spring-managed.
 *
 * @param <T> the Java record representing the tool's structured parameters
 */
public interface NativeTool<T> extends LocalTool<T> {

    /** The description explaining when and how the LLM should invoke the tool. */
    default @NonNull String getDescription() {
        return ToolDocs.descriptionOf(getArgsClass());
    }
}
