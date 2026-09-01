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
 * it concrete and example-driven. The standard {@code ####} sections are parsed and rendered in a
 * canonical order; annotation order does not control prompt order. Non-contract headings such as
 * implementation security notes are intentionally not emitted into the agent catalog. Leave it
 * empty to render only the tool's short description.
 *
 * <p>{@code Return format} owns every wire-visible success and failure shape. {@code Errors & edge
 * cases} may explain distinct triggers, recovery, limits, or policy implications, but must not
 * repeat a result body or restate a case already defined by an earlier section.
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
     * ToolDefinition#longDescription()}. Parsed into standard headings and rendered in fixed
     * semantic order by the prompt compiler. REQUIRED: every documented tool carries a real brief,
     * so the model sees when to reach for it, its behavior, return shape, and edges.
     */
    @NonNull String usage();

    /**
     * Content encoding of a successful result: {@link ToolResultFormat#JSON}, {@link
     * ToolResultFormat#PLAINTEXT}, or both. Failure is not a content format; it is carried by the
     * tool result's separate success flag and normally contains a plain diagnostic body.
     */
    @NonNull ToolResultFormat[] resultFormats();

    /**
     * Concrete usage examples (args-object strings). REQUIRED: one or more concrete {@code args}
     * objects the model would place in a {@code calls[]} entry.
     */
    @NonNull String[] examples();

    /**
     * One or two representative successful return-value shapes, not positionally aligned with
     * {@link #examples()}. Stable expected failure bodies belong in the normative Return format
     * section; their triggers and recovery belong under Errors &amp; edge cases. Failures never
     * belong in this example array. Rendered as explicitly illustrative fenced blocks after the
     * tool's own result contract. REQUIRED.
     */
    @NonNull String[] returnExamples();
}
