package top.focess.veto.command.commands;

import java.util.List;
import org.jetbrains.annotations.NotNull;
import top.focess.command.CommandResult;
import top.focess.command.CommandSender;
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
                    s.terminate("Goodbye.");
                    return CommandResult.ALLOW;
                });
    }

    @Override
    @NotNull
    public List<String> usage(@NotNull CommandSender s) {
        return List.of("/exit — Quit the terminal");
    }
}
