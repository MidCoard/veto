package top.focess.veto.command;

import top.focess.veto.contract.IpcFrame;
import top.focess.veto.contract.IpcMeta;

/**
 * Signals that the current prompt was cancelled by the user.
 *
 * <p><b>Safety net only.</b> The blocking {@link VetoCommandSender#input(String, boolean)} methods
 * return {@code null} on cancel — commands check for null and handle it directly. This exception is
 * only thrown if a command calls {@code inputAsync().join()} directly without handling {@link
 * java.util.concurrent.CancellationException}. {@link CommandRegistry} catches it as a safety net
 * and converts it into an {@link IpcFrame.Done} frame carrying {@link IpcMeta#CANCELLED} metadata.
 *
 * <p>This is a control-flow exception, not an error condition.
 */
public class CancelException extends RuntimeException {

    /** Constructs a new {@code CancelException} with the fixed message {@code "Cancelled."}. */
    public CancelException() {
        super("Cancelled.");
    }
}
