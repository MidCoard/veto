package top.focess.veto.command.commands;

import java.util.List;
import java.util.Map;
import top.focess.command.CommandSender;
import top.focess.veto.command.VetoCommand;

public class ExitCommand extends VetoCommand {

    public ExitCommand() {
        super("exit", "Quit the terminal", "quit");
    }

    @Override
    public void init() {
        addExecutor(
                (sender, args) -> {
                    vetoSender(sender).done(Map.of("exit", true));
                    return allow();
                });
    }

    @Override
    public List<String> usage(CommandSender s) {
        return List.of("/exit — Quit the terminal");
    }
}
