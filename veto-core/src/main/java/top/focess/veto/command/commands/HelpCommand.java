package top.focess.veto.command.commands;

import java.util.Comparator;
import java.util.List;
import org.jspecify.annotations.NonNull;
import top.focess.command.Command;
import top.focess.command.CommandResult;
import top.focess.command.CommandSender;
import top.focess.veto.command.CommandRegistry;
import top.focess.veto.command.VetoCommand;
import top.focess.veto.command.VetoCommandSender;

public class HelpCommand extends VetoCommand {

    private final @NonNull CommandRegistry registry;

    public HelpCommand(@NonNull CommandRegistry registry) {
        super("help", "Show available commands", "h");
        this.registry = registry;
    }

    @Override
    public void init() {
        addExecutor(
                (sender, args) -> {
                    VetoCommandSender s = vetoSender(sender);
                    if (s == null) return CommandResult.REFUSE;

                    // List every command this sender could actually execute — help included, so the
                    // user can discover /help itself — gated on the command's executor-permission
                    // predicate (the same one the dispatcher uses to allow/refuse execution), NOT
                    // on
                    // getPermission. Every Veto command defaults to CommandPermission.EVERYONE
                    // (the enum tier is never reassigned), so filtering on getPermission would
                    // admit every command to every sender — leaking login-gated commands
                    // (status/logout/agent/...) to an unauthenticated user. Login gating is applied
                    // via setExecutorPermission(LOGGED_IN), so this is the authoritative check.
                    List<Command> commands =
                            registry.getCommands().stream()
                                    .filter(c -> c.getExecutorPermission().test(sender))
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
    public @NonNull List<String> usage(@NonNull CommandSender s) {
        return List.of("/help — Show available commands");
    }
}
