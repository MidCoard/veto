package top.focess.veto.command;

import top.focess.veto.contract.IpcFrame;
import top.focess.veto.contract.IpcMeta;

/**
 * Signals that the current prompt was cancelled by the user.
 *
 * <p>Thrown by {@link VetoCommandSender#input(String, boolean)} (and the blocking {@link
 * VetoCommandSender#input()} convenience) when the terminal sends an {@link
 * top.focess.veto.contract.IpcFrame.Cancel} while a prompt is pending. {@link
 * VetoCommandSender#cancelCurrentPrompt()} completes the input future exceptionally with a {@link
 * java.util.concurrent.CancellationException}; this exception translates that into a control-flow
 * signal that commands can catch or let propagate.
 *
 * <p>{@link CommandRegistry} catches this exception during dispatch and converts it into an {@link
 * IpcFrame.Done} frame carrying {@link IpcMeta#CANCELLED} metadata — the same frame the
 * two-level-cancel handler in {@link top.focess.veto.terminal.IpcServer} would send for a level-2
 * (running) cancel. For a level-1 (prompted) cancel, the server does <em>not</em> send a terminal
 * frame itself (the command is expected to continue or exit gracefully); this exception provides
 * the command's exit path.
 *
 * <p>This is a control-flow exception, not an error condition.
 */
public class CancelException extends RuntimeException {

    /** Constructs a new {@code CancelException} with the fixed message {@code "Cancelled."}. */
    public CancelException() {
        super("Cancelled.");
    }
}
