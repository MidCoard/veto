package top.focess.veto.command;

import java.util.function.Predicate;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import top.focess.command.Command;
import top.focess.command.CommandArgument;
import top.focess.command.CommandPermission;
import top.focess.command.CommandSender;
import top.focess.command.DataConverter;

/**
 * Base command for the Veto terminal. All commands extend this and override {@link #init} to
 * register executors via {@link #addExecutor}.
 *
 * <h3>Convenience factories</h3>
 *
 * {@link #arg(String)} creates a required named string argument. {@link #opt(String)} creates an
 * optional one. {@link #fixed(String)} creates a fixed literal for sub-command routing.
 *
 * <h3>Permission model</h3>
 *
 * Default permission is {@link CommandPermission#EVERYONE}. Commands that require login should call
 * {@link #setExecutorPermission(Predicate)} with {@link #LOGGED_IN} in their constructor.
 */
public abstract class VetoCommand extends Command {

    /** Predicate that matches only logged-in Veto senders. */
    @NotNull
    protected static final Predicate<CommandSender> LOGGED_IN =
            s -> s instanceof VetoCommandSender vs && vs.isLoggedIn();

    protected VetoCommand(
            @NotNull String name, @NotNull String description, @NotNull String... aliases) {
        super(name, description, aliases);
        setPermission(CommandPermission.EVERYONE);
    }

    // ── argument factories ─────────────────────────────────────────────────

    /** A required, named string argument. */
    @NotNull
    protected static CommandArgument<String> arg(@NotNull String name) {
        return CommandArgument.ofString().named(name);
    }

    /** An optional, named string argument (null when omitted). */
    @NotNull
    protected static CommandArgument<String> opt(@NotNull String name) {
        return CommandArgument.ofNullable(DataConverter.DEFAULT_DATA_CONVERTER).named(name);
    }

    /** A fixed literal value for sub-command routing (e.g. "create", "list"). */
    @NotNull
    protected static CommandArgument<String> fixed(@NotNull String value) {
        return CommandArgument.of(value);
    }

    // ── sender helpers ─────────────────────────────────────────────────────

    /** Cast the sender to a {@link VetoCommandSender}, or null if it's a different type. */
    @Nullable
    protected static VetoCommandSender vetoSender(@NotNull CommandSender sender) {
        return sender instanceof VetoCommandSender vs ? vs : null;
    }
}
