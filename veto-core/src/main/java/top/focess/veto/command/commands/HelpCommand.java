package top.focess.veto.command.commands;

import java.util.List;
import java.util.Map;
import top.focess.command.Command;
import top.focess.command.CommandSender;
import top.focess.veto.command.VetoCommand;
import top.focess.veto.command.VetoCommandSender;

public class HelpCommand extends VetoCommand {

    public HelpCommand() {
        super("help", "Show available commands", "h");
    }

    @Override
    public void init() {
        addExecutor(
                (sender, args) -> {
                    VetoCommandSender s = vetoSender(sender);
                    // Get the manager this command is registered on
                    var manager = getManager();
                    StringBuilder sb = new StringBuilder();
                    int max = 10;
                    for (Command c : manager.getCommands()) {
                        if (c.getName().equals("help")) continue;
                        if (!sender.hasPermission(c.getPermission())) continue;
                        int len = c.getName().length();
                        if (len > max) max = len;
                    }
                    for (Command c : manager.getCommands()) {
                        if (c.getName().equals("help")) continue;
                        if (!sender.hasPermission(c.getPermission())) continue;
                        sb.append(
                                String.format(
                                        "  /%-" + max + "s  %s\n",
                                        c.getName(),
                                        c.getDescription()));
                    }
                    sb.append("\nType anything to chat with the agent.");
                    s.delta(sb.toString());
                    s.done(Map.of());
                    return allow();
                });
    }

    @Override
    public List<String> usage(CommandSender s) {
        return List.of("/help — Show available commands");
    }
}
