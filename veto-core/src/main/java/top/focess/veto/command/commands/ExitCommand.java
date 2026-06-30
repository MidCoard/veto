package top.focess.veto.command.commands;

import java.util.List;
import org.jspecify.annotations.NonNull;
import top.focess.command.CommandResult;
import top.focess.command.CommandSender;
import top.focess.veto.command.TerminateException;
import top.focess.veto.command.VetoCommand;
import top.focess.veto.command.VetoCommandSender;

public class ExitCommand extends VetoCommand {

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
