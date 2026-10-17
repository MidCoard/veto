package top.focess.veto.session;

/** The saved answer requested for citation checking is unavailable. */
public final class AnswerNotFoundException extends IllegalArgumentException {
    public AnswerNotFoundException() {
        super("Saved answer is not available");
    }
}
