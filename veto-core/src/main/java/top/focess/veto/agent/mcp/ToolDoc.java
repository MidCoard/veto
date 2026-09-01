package top.focess.veto.agent.mcp;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.jspecify.annotations.NonNull;

/**
 * LLM-facing documentation for a tool, declared on its args record or its enclosing tool class.
 * Carries a one-liner {@link #description()}, typed documentation sections, and concrete
 * call/result examples. Reflected at load time into {@link ToolDefinition#documentation()} so
 * section identity is preserved through prompt rendering. Parallels {@link Doc} at the whole-tool
 * level.
 *
 * <p>Resolution (see {@link ToolDocs#toolDocOf(Class)}): the annotation is read directly off the
 * args class; if absent there, off the args class's enclosing tool class. So a tool may declare
 * {@code @ToolDoc} on its args record (e.g. {@code ListDirTool.Args}, {@code LoadSkillArgs}) or on
 * its enclosing bean class (e.g. the nested agent tools in {@code MemoryTools}); both render.
 *
 * <p>Each semantic block has its own annotation member. Do not embed Markdown headings in a field.
 * The prompt renderer owns heading names and canonical order. {@link #resultContract()} owns every
 * wire-visible success and failure shape; {@link #errorsAndEdgeCases()} explains distinct triggers,
 * recovery, limits, and policy implications without repeating result bodies.
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

    /** What the tool does after a valid call reaches its handler. */
    @NonNull String behavior();

    /** Positive selection guidance. */
    @NonNull String whenToUse();

    /** Negative selection guidance and alternatives. */
    @NonNull String whenNotToUse();

    /** Normative success and failure content shapes. */
    @NonNull String resultContract();

    /** Non-duplicative limits, recovery guidance, and edge conditions. */
    @NonNull String errorsAndEdgeCases();

    /** Internal security classification and enforcement notes. */
    @NonNull String security();

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
     * {@link #resultContract()}. REQUIRED.
     */
    @NonNull String[] returnExamples();
}
