package top.focess.veto.agent.loop;

/**
 * Branch on a programmatic {@link Check} over {@link Scope} vars (LLD §3.3 {@code
 * conditional_goto}). Zero model calls.
 */
public record ConditionalGotoAction(
        String id, String label, Check check, int trueGoto, Integer falseGoto) implements Action {

    public ConditionalGotoAction {
        if (check == null) {
            throw new IllegalArgumentException("conditional_goto requires a check");
        }
    }

    /**
     * The next program counter: {@code trueGoto} if the check passes, else {@code falseGoto} (or
     * {@code fallback}).
     */
    public int nextPc(boolean passed, int fallback) {
        return passed ? trueGoto : (falseGoto != null ? falseGoto : fallback);
    }
}
