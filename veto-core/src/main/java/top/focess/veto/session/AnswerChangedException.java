package top.focess.veto.session;

/** The saved answer changed before citation checking could complete. */
public final class AnswerChangedException extends IllegalArgumentException {
    public AnswerChangedException() {
        super("Saved answer changed");
    }
}
