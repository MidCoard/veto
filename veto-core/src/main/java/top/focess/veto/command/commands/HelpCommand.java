package top.focess.veto.command.commands;

import java.util.Comparator;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import top.focess.command.Command;
import top.focess.command.CommandResult;
import top.focess.command.CommandSender;
import top.focess.veto.command.CommandRegistry;
import top.focess.veto.command.VetoCommand;
import top.focess.veto.command.VetoCommandSender;

public class HelpCommand extends VetoCommand {

    private final CommandRegistry registry;

    public HelpCommand(@NotNull CommandRegistry registry) {
        super("help", "Show available commands", "h");
        this.registry = registry;
    }

    @Override
    public void init() {
        addExecutor(
                (sender, args) -> {
                    VetoCommandSender s = vetoSender(sender);
                    if (s == null) return CommandResult.REFUSE;

                    List<Command> commands =
                            registry.getCommands().stream()
                                    .filter(c -> !"help".equals(c.getName()))
                                    .filter(c -> sender.hasPermission(c.getPermission()))
                                    .sorted(Comparator.comparing(Command::getName))
                                    .toList();

                    int maxLen =
                            commands.stream().mapToInt(c -> c.getName().length()).max().orElse(10);

                    s.output("Available Commands");
                    for (Command c : commands) {
                        s.output(
                                String.format(
                                        "  /%-" + maxLen + "s  %s",
                                        c.getName(),
                                        c.getDescription()));
                    }
                    s.output("");
                    s.output("Type anything to chat with the agent.");
                    return CommandResult.ALLOW;
                });
    }

    @Override
    @NotNull
    public List<String> usage(@NotNull CommandSender s) {
        return List.of("/help — Show available commands");
    }
}
