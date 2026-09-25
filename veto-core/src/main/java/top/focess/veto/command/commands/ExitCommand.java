package top.focess.veto.command.commands;

import java.util.List;
import org.jspecify.annotations.NonNull;
import top.focess.command.CommandResult;
import top.focess.command.CommandSender;
import top.focess.veto.command.TerminateException;
import top.focess.veto.command.VetoCommand;
import top.focess.veto.command.VetoCommandSender;

/** Terminates the terminal session ({@code /exit}, alias {@code /quit}). */
public class ExitCommand extends VetoCommand {

    /** Constructs the {@code /exit} command. */
    public ExitCommand() {
        super("exit", "Quit the terminal", "quit");
    }

    @Override
    public void init() {
        addExecutor(
                (sender, args) -> {
                    VetoCommandSender s = vetoSender(sender);
                    if (s == null) return CommandResult.REFUSE;
                    throw new TerminateException("Goodbye.");
                });
    }

    @Override
    public @NonNull List<String> usage(@NonNull CommandSender s) {
        return List.of("/exit — Quit the terminal");
    }
}
