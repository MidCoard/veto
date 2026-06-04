package top.focess.veto.command.commands;

import java.util.Collection;
import java.util.List;
import top.focess.command.Command;
import top.focess.command.CommandSender;
import top.focess.veto.command.TerminalIO;
import top.focess.veto.command.VetoCommand;
import top.focess.veto.contract.ResponseType;
import top.focess.veto.contract.TerminalResponse;

public class HelpCommand extends VetoCommand {

    private final Collection<Command> all;

    public HelpCommand(Collection<Command> all) {
        super("help", "Show available commands", "h");
        this.all = all;
        addExecutor(
                (sender, args, io) -> {
                    TerminalIO tio = (TerminalIO) io;
                    StringBuilder sb = new StringBuilder();
                    int max =
                            all.stream()
                                    .filter(c -> !c.getName().equals("help"))
                                    .mapToInt(c -> c.getName().length())
                                    .max()
                                    .orElse(10);
                    for (Command c : all) {
                        if (c.getName().equals("help")) continue;
                        String desc = c instanceof VetoCommand vc ? vc.getDescription() : "";
                        sb.append(String.format("  /%-" + max + "s  %s\n", c.getName(), desc));
                    }
                    sb.append("\nType anything to chat with the agent.");
                    tio.respond(new TerminalResponse(ResponseType.MESSAGE, sb.toString()));
                    return allow();
                });
    }

    @Override
    public void init() {
    }

    @Override
    public List<String> usage(CommandSender s) {
        return List.of("/help");
    }
}
