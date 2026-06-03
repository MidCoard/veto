package top.focess.veto.command.commands;

import java.util.Collection;
import java.util.Map;

import top.focess.command.Command;
import top.focess.command.CommandResult;
import top.focess.veto.command.TerminalIO;
import top.focess.veto.contract.ResponseType;
import top.focess.veto.contract.TerminalResponse;

public class HelpCommand extends Command {

    private final Collection<Command> all;
    private final Map<String, String> descriptions;

    public HelpCommand(Collection<Command> all, Map<String, String> descriptions) {
        super("help", "h");
        this.all = all;
        this.descriptions = descriptions;
        addExecutor(
                (sender, args, io) -> {
                    TerminalIO tio = (TerminalIO) io;
                    StringBuilder sb = new StringBuilder();
                    int maxName = all.stream().mapToInt(c -> c.getName().length()).max().orElse(10);
                    for (Command c : all) {
                        if (c.getName().equals("help")) continue;
                        String desc = descriptions.getOrDefault(c.getName(), "");
                        sb.append(String.format("  /%-" + maxName + "s  %s\n", c.getName(), desc));
                    }
                    sb.append("\nType anything to chat with the agent.");
                    tio.respond(new TerminalResponse(ResponseType.MESSAGE, sb.toString()));
                    return CommandResult.ALLOW;
                });
    }

    @Override
    public void init() {
    }

    @Override
    public java.util.List<String> usage(top.focess.command.CommandSender s) {
        return java.util.List.of("/help");
    }
}
