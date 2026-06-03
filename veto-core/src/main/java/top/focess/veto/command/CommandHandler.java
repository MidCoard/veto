package top.focess.veto.command;

import java.util.List;
import java.util.Map;

import top.focess.veto.contract.TerminalResponse;

/**
 * A command registered in the {@link CommandRegistry}. Each handler declares its name, argument
 * schema, and help text, and implements {@link #execute} with the real business logic.
 *
 * <p>Implementations are wired via Spring configuration; they may depend on services (AgentService,
 * SessionManager, etc.).
 */
public interface CommandHandler {

    /**
     * The command verb as typed by the user (e.g. "send", "login", "status").
     */
    String name();

    /**
     * One-line description for help output.
     */
    String description();

    /**
     * Usage string, e.g. "send &lt;message&gt;".
     */
    String usage();

    /**
     * Argument definitions for help generation.
     */
    List<ArgDef> arguments();

    /**
     * Metadata snapshot for dynamic help / command listing.
     */
    default CommandMetadata metadata() {
        return new CommandMetadata(name(), description(), usage(), arguments());
    }

    /**
     * Execute the command.
     *
     * @param args         argument map parsed from user input
     * @param sessionToken current session token, or null if not logged in
     * @return structured response for terminal rendering
     */
    TerminalResponse execute(Map<String, Object> args, String sessionToken);
}
