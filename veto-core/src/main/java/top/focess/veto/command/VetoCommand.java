package top.focess.veto.command;

import java.util.function.Predicate;
import top.focess.command.Command;
import top.focess.command.CommandArgument;
import top.focess.command.CommandPermission;
import top.focess.command.CommandResult;
import top.focess.command.CommandSender;
import top.focess.command.DataConverter;

/**
 * Base command for the Veto terminal. All commands must override {@link #init()} and call {@link
 * #addExecutor} there. The default permission is {@link CommandPermission#EVERYONE} — use {@link
 * #setPermission} in the constructor for restricted commands and {@link
 * Command.Executor#addExecutorPermission(Predicate)} for login-gated executors.
 */
public abstract class VetoCommand extends Command {

    /** Predicate that only matches logged-in Veto senders. */
    protected static final Predicate<CommandSender> LOGGED_IN =
            s -> s instanceof VetoCommandSender vs && vs.isLoggedIn();

    protected VetoCommand(String name, String description, String... aliases) {
        super(name, description, aliases);
        setPermission(CommandPermission.EVERYONE);
    }

    protected static CommandResult allow() {
        return CommandResult.ALLOW;
    }

    protected static CommandResult refuse() {
        return CommandResult.REFUSE;
    }

    protected static CommandArgument<String> arg(String name) {
        return CommandArgument.ofString().named(name);
    }

    protected static CommandArgument<String> opt(String name) {
        return CommandArgument.ofNullable(DataConverter.DEFAULT_DATA_CONVERTER).named(name);
    }

    protected static CommandArgument<String> fixed(String value) {
        return CommandArgument.of(value);
    }

    /** Convenience: cast sender and return it, or null if not a VetoCommandSender. */
    protected static VetoCommandSender vetoSender(CommandSender sender) {
        return sender instanceof VetoCommandSender vs ? vs : null;
    }
}
