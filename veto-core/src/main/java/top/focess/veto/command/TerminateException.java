package top.focess.veto.command;

import org.jspecify.annotations.NonNull;
import top.focess.veto.contract.IpcFrame;

/**
 * Signals that the current terminal session must be forcefully terminated.
 *
 * <p>Thrown by command handlers (e.g. {@code /exit}) when the backend decides the session should
 * end. {@link CommandRegistry} catches this exception during dispatch and converts it into an
 * {@link IpcFrame.Terminate} frame, which is sent to the terminal so it can display the reason and
 * shut down cleanly.
 *
 * <p>This is a control-flow exception, not an error condition.
 */
public class TerminateException extends RuntimeException {

    /** Human-readable explanation shown to the terminal user before the connection is closed. */
    private final @NonNull String reason;

    /**
     * Constructs a new {@code TerminateException} with the given termination reason.
     *
     * @param reason the message to display to the user; must not be {@code null}
     */
    public
    @NonNull
    TerminateException(@NonNull String reason) {
        super(reason);
        this.reason = reason;
    }

    /**
     * Returns the human-readable reason for the termination.
     *
     * @return the termination reason string; never {@code null}
     */
    public String getReason() {
        return reason;
    }
}
