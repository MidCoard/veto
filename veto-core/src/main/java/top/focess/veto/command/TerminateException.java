package top.focess.veto.command;

public class TerminateException extends RuntimeException {
    private final String reason;

    public TerminateException(String reason) {
        super(reason);
        this.reason = reason;
    }

    public String getReason() {
        return reason;
    }
}
