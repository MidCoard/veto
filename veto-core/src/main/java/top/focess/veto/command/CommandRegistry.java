package top.focess.veto.command;

import jakarta.annotation.PostConstruct;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import top.focess.command.*;
import top.focess.veto.contract.ResponseType;
import top.focess.veto.contract.TerminalResponse;

/**
 * Registry that wraps the {@link CommandManager} from {@code focess-command} and adapts it for the
 * Veto terminal environment.
 *
 * <p>Command dispatch is delegated to {@link CommandManager#dispatch(CommandSender, String,
 * IOHandler)} which handles input splitting (with quote support), command lookup, argument parsing,
 * and execution — eliminating the need for manual parsing in this class.
 */
@Component
public class CommandRegistry {

    private static final Logger log = LoggerFactory.getLogger(CommandRegistry.class);

    private final CommandManager manager = new CommandManager();
    private PromptHandler promptHandler;
    private TerminalSessionManager sessionManager;

    public void setPromptHandler(PromptHandler h) {
        this.promptHandler = h;
    }

    public void setTerminalSessionManager(TerminalSessionManager sm) {
        this.sessionManager = sm;
    }

    public void register(Command c) {
        manager.register(c);
    }

    @PostConstruct
    public void init() {
        log.info("Registry: {} commands", manager.getCommands().size());
    }

    /**
     * Dispatch raw terminal input. Plain text (no leading {@code /}) is routed to the {@link
     * PromptHandler}. Prefixed input is delegated to {@link CommandManager#dispatch(CommandSender,
     * String, IOHandler)} which handles splitting, lookup, and execution.
     */
    /**
     * Magic prefix that signals a Tab-completion request instead of a normal command dispatch.
     */
    private static final String COMPLETION_PREFIX = "\0complete:";

    public TerminalResponse dispatch(String raw, String sessionToken, TerminalIO io) {
        if (raw == null || raw.isBlank()) return TerminalResponse.error("Empty input");

        // ── Completion protocol: "\0complete:<partial>" returns matching commands ──
        if (raw.startsWith(COMPLETION_PREFIX)) {
            String partial = raw.substring(COMPLETION_PREFIX.length());
            List<String> completions = complete(partial, sessionToken);
            io.respond(new TerminalResponse(ResponseType.LIST, String.join("\n", completions)));
            // Return null — caller (TerminalChannel) sees hasResponded() == true and skips its own
            // write
            return io.getResponse();
        }

        // Plain text → agent prompt
        if (!raw.trim().startsWith("/")) {
            if (promptHandler == null) return TerminalResponse.error("Agent not available");
            return promptHandler.handle(raw.trim(), sessionToken);
        }

        // Strip leading "/" — CommandManager.dispatch takes the bare command name as first token
        String input = raw.trim().substring(1);

        String username = sessionManager != null ? sessionManager.resolve(sessionToken) : null;
        VetoCommandSender sender = new VetoCommandSender(username);

        try {
            ExecutionResult result = manager.dispatch(sender, input, io);
            CommandResult cr = result.getResult();

            if (cr == CommandResult.COMMAND_NOT_FOUND) {
                String cmdName = input.split("\\s+", 2)[0].toLowerCase();
                return unknown(cmdName);
            }
            if (cr == CommandResult.ARGS_NOT_EXECUTED || cr == CommandResult.REFUSE_EXCEPTION) {
                return TerminalResponse.error(result.getMessage().orElse("Command failed"));
            }
            TerminalResponse resp = io.getResponse();
            return resp != null ? resp : TerminalResponse.error("No response");
        } catch (Exception e) {
            log.error("Dispatch failed", e);
            return TerminalResponse.error(e.getMessage());
        }
    }

    /**
     * Tab-completion via {@link CommandManager#complete(CommandSender, String)}.
     *
     * <p>Trailing whitespace is preserved so that {@code "pattern "} routes to {@code
     * PatternCommand}'s argument completer (showing sub-commands), whereas {@code "pattern"}
     * returns command names starting with "pattern".
     */
    public List<String> complete(String partial, String sessionToken) {
        if (partial == null || partial.isBlank()) return List.of();

        // Strip only leading whitespace and leading "/" — trailing whitespace is
        // semantically significant: "pattern " means "complete the first argument of pattern"
        String input = partial.stripLeading();
        if (input.startsWith("/")) input = input.substring(1);

        String username = sessionManager != null ? sessionManager.resolve(sessionToken) : null;
        VetoCommandSender sender = new VetoCommandSender(username);

        List<String> completions = manager.complete(sender, input);

        // Prepend "/" only when completing the command name itself (first token).
        // Check the original input (before trim) for a space — "pattern " has one,
        // meaning we're completing args; "log" has none → command-name completion.
        // Use stripTrailing() to detect: if stripping trailing space changes the string,
        // there was a trailing space → args completion.
        boolean hasTrailingSpace = !input.equals(input.stripTrailing());
        boolean hasSpaceWithin = input.stripTrailing().contains(" ");
        if (!hasTrailingSpace && !hasSpaceWithin) {
            return completions.stream().map(c -> "/" + c).toList();
        }
        return completions;
    }

    private TerminalResponse unknown(String cmd) {
        return new TerminalResponse(
                ResponseType.ERROR,
                "Unknown: /" + cmd + " — try /help",
                Map.of("suggestions", List.of("Try /help")));
    }

    public Collection<Command> all() {
        return manager.getCommands();
    }
}
