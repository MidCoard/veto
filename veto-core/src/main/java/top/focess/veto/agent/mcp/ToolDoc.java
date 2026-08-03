package top.focess.veto.agent.mcp;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.jspecify.annotations.NonNull;

/**
 * LLM-facing documentation for a tool, declared on its args record. Carries a one-liner {@link
 * #description()} (what the tool is), a long-form {@link #usage()} (multi-paragraph usage doc), and
 * concrete {@link #examples()} (args-object strings). Reflected at load time into {@link
 * ToolDefinition#description()}, {@link ToolDefinition#longDescription()}, and {@link
 * ToolDefinition#examples()}, which the prompt compiler renders under the tool's catalog entry.
 * Parallels {@link Doc} (per-parameter descriptions) at the whole-tool level.
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

    /** One-liner for the manifest header — what the tool is. */
    @NonNull String description() default "";

    /**
     * 6-section long-form usage doc — how and when to use it. Surfaces as {@link
     * ToolDefinition#longDescription()}. Rendered verbatim as the body of the tool's catalog entry
     * by the prompt compiler. Empty by default (the tool's short {@code description} is used
     * alone).
     */
    @NonNull String usage() default "";

    /** Concrete usage examples (args-object strings); empty by default. */
    @NonNull String[] examples() default {};

    /**
     * Concrete return-value examples; the i-th entry corresponds to the i-th {@link #examples()}
     * entry (and the counts must match). Each must obey the plaintext result format: {@code \n} as
     * the line/record splitter, flat {@code key=value} inline fields, a short prose header, no
     * pseudo-JSON and no truncation. Empty by default.
     */
    @NonNull String[] returnExamples() default {};
}
