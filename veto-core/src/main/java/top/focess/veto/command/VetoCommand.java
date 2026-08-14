package top.focess.veto.command;

import java.util.function.Predicate;
import org.jspecify.annotations.NonNull;
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
    protected static final @NonNull Predicate<CommandSender> LOGGED_IN =
            s -> s instanceof VetoCommandSender vs && vs.isLoggedIn();

    protected VetoCommand(
            @NonNull String name, @NonNull String description, String @NonNull ... aliases) {
        super(name, description, aliases);
    }

    // ── argument factories ─────────────────────────────────────────────────

    /** A required, named string argument. */
    protected static @NonNull CommandArgument<String> arg(@NonNull String name) {
        return CommandArgument.ofString().named(name);
    }

    /** An optional, named string argument (null when omitted). */
    protected static @NonNull CommandArgument<String> opt(@NonNull String name) {
        return CommandArgument.ofNullable(DataConverter.DEFAULT_DATA_CONVERTER).named(name);
    }

    /** A fixed literal value for sub-command routing (e.g. "create", "list"). */
    protected static @NonNull CommandArgument<String> fixed(@NonNull String value) {
        return CommandArgument.of(value);
    }

    /**
     * Reasserts the command framework's required-argument contract at the application boundary. A
     * malformed dispatch therefore produces a named contract error instead of a later NPE.
     */
    protected static <T> @NonNull T requiredArg(T value, @NonNull String name) {
        if (value == null) {
            throw new IllegalArgumentException("Missing required command argument: " + name);
        }
        return value;
    }

    // ── sender helpers ─────────────────────────────────────────────────────

    /** Cast the sender to a {@link VetoCommandSender}, or null if it's a different type. */
    protected static VetoCommandSender vetoSender(@NonNull CommandSender sender) {
        return sender instanceof VetoCommandSender vs ? vs : null;
    }
}
