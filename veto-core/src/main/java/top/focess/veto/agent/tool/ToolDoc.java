package top.focess.veto.agent.tool;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.jspecify.annotations.NonNull;

/**
 * LLM-facing documentation declared on a tool implementation class. Carries a one-liner {@link
 * #description()}, typed documentation sections, and concrete call/result examples. Reflected at
 * load time into {@link ToolDefinition#documentation()} so section identity is preserved through
 * prompt rendering. Parallels {@link Doc} at the whole-tool level.
 *
 * <p>Resolution (see {@link ToolDocs#toolDocOf(Class)}) uses the tool implementation class,
 * including inherited tool documentation. Argument records carry only parameter annotations such as
 * {@link Doc}; their location and enclosing class do not determine tool documentation.
 *
 * <p>Each semantic block has its own annotation member. Do not embed Markdown headings in a field.
 * The prompt renderer owns heading names and canonical order. {@link #resultContract()} owns every
 * wire-visible success and failure shape; {@link #errorsAndEdgeCases()} explains distinct triggers,
 * recovery, limits, and policy implications without repeating result bodies.
 *
 * <p>Each example string is a concrete arguments object (the JSON arguments of one native call),
 * e.g. one showing a required argument and another showing an optional one. Declare one or more to
 * convey argument shapes the description alone cannot. Tools without a {@code @ToolDoc} render
 * exactly as before (short description only).
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
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

    /** Agent-facing access restrictions and obligations. */
    @NonNull String security();

    /**
     * Content encoding of a successful result: {@link ToolResultFormat#JSON}, {@link
     * ToolResultFormat#PLAINTEXT}, or both. Failure is not a content format; it is carried by the
     * tool result's separate success flag and normally contains a plain diagnostic body.
     */
    @NonNull ToolResultFormat @NonNull [] resultFormats();

    /**
     * Concrete usage examples (arguments-object strings). REQUIRED: one or more concrete arguments
     * objects passed to a native call.
     */
    @NonNull String @NonNull [] examples();

    /**
     * Representative successful return-value shapes, positionally aligned with {@link #examples()}:
     * entry {@code i} is the success result of the call shown in {@code examples()[i]}. Tools with
     * arguments declare three to five pairs; a no-argument tool declares exactly one empty-call
     * pair. Stable expected failure bodies belong in the normative Return format section; their
     * triggers and recovery belong under Errors &amp; edge cases. Failures never belong in this
     * example array. Rendered as explicitly illustrative fenced blocks after the {@link
     * #resultContract()}. REQUIRED.
     */
    @NonNull String @NonNull [] returnExamples();
}
