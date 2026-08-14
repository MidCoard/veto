package top.focess.veto.agent.loop;

import org.jspecify.annotations.NonNull;

/**
 * Branch on a programmatic {@link Check} over {@link Scope} vars ( {@code conditional_goto}). Zero
 * model calls.
 */
public record ConditionalGotoAction(
        @NonNull String id,
        @NonNull String label,
        @NonNull Check check,
        int trueGoto,
        Integer falseGoto)
        implements Action {

    /**
     * The next program counter: {@code trueGoto} if the check passes, else {@code falseGoto} (or
     * {@code fallback}).
     */
    public int nextPc(boolean passed, int fallback) {
        return passed ? trueGoto : (falseGoto != null ? falseGoto : fallback);
    }
}
