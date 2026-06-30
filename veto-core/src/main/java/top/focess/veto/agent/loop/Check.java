package top.focess.veto.agent.loop;

import java.util.List;
import org.jspecify.annotations.NonNull;

/**
 * A programmatic check over {@link Scope} vars. Executed by {@link CheckEvaluator}
 * deterministically with <b>zero LLM calls</b> (except {@link Llm}, which is one model call — a
 * signal that the task wasn't actually predictable). {@code CURRENT_STEPS} is a readable var
 * auto-incremented by the loop.
 */
public sealed interface Check
        permits Check.Equals,
                Check.NotEquals,
                Check.Contains,
                Check.Matches,
                Check.Empty,
                Check.NotEmpty,
                Check.Numeric,
                Check.ExitOk,
                Check.Llm {

    /** {@code $var == value}. */
    record Equals(@NonNull String var, @NonNull String value) implements Check {}

    /** {@code $var!= value}. */
    record NotEquals(@NonNull String var, @NonNull String value) implements Check {}

    /** {@code $var} contains {@code substring}. */
    record Contains(@NonNull String var, @NonNull String substring) implements Check {}

    /** {@code $var} matches {@code regex}. */
    record Matches(@NonNull String var, @NonNull String regex) implements Check {}

    /** {@code $var} is empty (or unset → undefined sentinel). */
    record Empty(@NonNull String var) implements Check {}

    /** {@code $var} is non-empty. */
    record NotEmpty(@NonNull String var) implements Check {}

    /** Numeric comparison: {@code $var <op> value} (op in gt/lt/eq/gte/lte). */
    record Numeric(@NonNull String var, @NonNull String op, @NonNull String value)
            implements Check {}

    /** Did the referenced step exit ok (success)? */
    record ExitOk(@NonNull String stepId) implements Check {}

    /** A genuinely semantic judgment — one model call. Reserve for decisions checks cannot make. */
    record Llm(@NonNull String prompt, @NonNull String var) implements Check {}

    /** The operands a check references (for validation). */
    default List<String> operands() {
        return switch (this) {
            case Equals e -> List.of(e.var());
            case NotEquals e -> List.of(e.var());
            case Contains c -> List.of(c.var());
            case Matches m -> List.of(m.var());
            case Empty e -> List.of(e.var());
            case NotEmpty e -> List.of(e.var());
            case Numeric n -> List.of(n.var());
            case ExitOk e -> List.of(e.stepId());
            case Llm l -> List.of(l.var());
        };
    }
}
