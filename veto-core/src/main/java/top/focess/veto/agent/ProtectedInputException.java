package top.focess.veto.agent;

/** Input protection stopped publication to the primary model. */
public final class ProtectedInputException extends IllegalStateException {
    public ProtectedInputException() {
        super("Input protection failed; check the secret-protection plugin and its local model");
    }
}
