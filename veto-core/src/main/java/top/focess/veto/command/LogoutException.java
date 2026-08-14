package top.focess.veto.command;

import top.focess.veto.contract.IpcFrame;
import top.focess.veto.contract.IpcMeta;

/**
 * Signals that the current user has logged out of the Veto terminal session.
 *
 * <p>Thrown by command handlers (e.g. {@code /logout}) to indicate a voluntary logout. {@link
 * CommandRegistry} catches this exception during dispatch and converts it into an {@link
 * IpcFrame.Done} frame carrying {@link IpcMeta#CLEAR_SESSION} metadata, which instructs the
 * terminal to clear its cached session state (username, turn count, etc.).
 *
 * <p>This is a control-flow exception, not an error condition.
 */
@SuppressWarnings("serial")
public class LogoutException extends RuntimeException {

    /** Constructs a new {@code LogoutException} with the fixed message {@code "Logged out."}. */
    public LogoutException() {
        super("Logged out.");
    }
}
