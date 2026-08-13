package top.focess.veto.agent.mcp;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.jspecify.annotations.NonNull;

/**
 * LLM-facing documentation for a tool, declared on its args record or its enclosing tool class.
 * Carries a one-liner {@link #description()} (what the tool is), a long-form {@link #usage()}
 * (multi-paragraph usage doc), and concrete {@link #examples()} (args-object strings). Reflected at
 * load time into {@link ToolDefinition#description()}, {@link ToolDefinition#longDescription()},
 * and {@link ToolDefinition#examples()}, which the prompt compiler renders under the tool's catalog
 * entry. Parallels {@link Doc} (per-parameter descriptions) at the whole-tool level.
 *
 * <p>Resolution (see {@link ToolDocs#toolDocOf(Class)}): the annotation is read directly off the
 * args class; if absent there, off the args class's enclosing tool class. So a tool may declare
 * {@code @ToolDoc} on its args record (e.g. {@code ListDirTool.Args}, {@code LoadSkillArgs}) or on
 * its enclosing bean class (e.g. the nested agent tools in {@code MemoryTools}); both render.
 *
 * <p>The {@link #description()} is the one-liner for the manifest header — what the tool is. The
 * {@link #usage()} is the deep, whole-tool brief the model reads before deciding to call a tool:
 * when to reach for it, when not to, how it behaves, what it returns and the edges that bite. Keep
 * it concrete and example-driven; it is rendered verbatim under the tool's heading. Leave it empty
 * to render only the tool's short description (e.g. for trivial tools).
 *
 * <p>Each example string is a concrete {@code args} object (the JSON the model would place in a
 * {@code calls[]} entry), e.g. one showing a required argument and another showing an optional one.
 * Declare one or more to convey arg shapes the description alone cannot. Tools without a
 * {@code @ToolDoc} render exactly as before (short description only).
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ToolDoc {

    /**
     * One-liner for the manifest header — what the tool is. REQUIRED: every documented tool must
     * state what it is.
     */
    @NonNull String description();

    /**
     * Long-form usage doc — how and when to use it. Surfaces as {@link
     * ToolDefinition#longDescription()}. Rendered verbatim as the body of the tool's catalog entry
     * by the prompt compiler. REQUIRED: every documented tool carries a real brief, so the model
     * always sees when to reach for it, when not to, its behavior, return shape, and edges.
     */
    @NonNull String usage();

    /**
     * Concrete usage examples (args-object strings). REQUIRED: one or more concrete {@code args}
     * objects the model would place in a {@code calls[]} entry.
     */
    @NonNull String[] examples();

    /**
     * Concrete return-value examples - one or two REPRESENTATIVE shapes in the tool's declared
     * output kind (CONTENT verbatim text / OUTCOME status JSON / DATA JSON; see {@code
     * plans/mvp-core/part5_agent/tools/tool_output_contract_lld.md}), not positionally aligned with
     * {@link #examples()}. Rendered fenced under the tool's catalog entry by the prompt compiler.
     * The uniform error envelope and the reserved REFUSED grammar are taught once in the "## Tool
     * Result Conventions" block, so per-tool entries should show success shapes (plus tool-specific
     * edge markers like {@code (no matches)}), not repeat the error grammar. REQUIRED.
     */
    @NonNull String[] returnExamples();
}
