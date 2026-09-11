package top.focess.veto.agent;

/** Safe submission failure: no protected input was accepted into the task queue. */
public final class ProtectedInputException extends IllegalStateException {
    public ProtectedInputException() {
        super("Protected input could not be processed; retry or use credential settings");
    }
}
