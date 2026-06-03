package top.focess.veto.command;

import jakarta.annotation.PostConstruct;

import java.util.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import top.focess.veto.contract.ResponseType;
import top.focess.veto.contract.TerminalResponse;

/**
 * Central command registration and dispatch. At startup, all {@link CommandHandler} instances are
 * registered via Spring configuration so raw input strings can be routed to the correct handler.
 *
 * <p>This component replaces the ad-hoc {@code switch(command)} block previously in
 * TerminalChannel.
 */
@Component
public class CommandRegistry {

    private static final Logger log = LoggerFactory.getLogger(CommandRegistry.class);

    private final Map<String, CommandHandler> handlers = new LinkedHashMap<>();

    /**
     * Register a handler. Called during {@link #init()} or externally via configuration.
     */
    public void register(CommandHandler handler) {
        handlers.put(handler.name(), handler);
        log.debug("Registered command: {}", handler.name());
    }

    @PostConstruct
    public void init() {
        log.info("CommandRegistry initialized with {} commands", handlers.size());
    }

    /**
     * Dispatch a raw input string to the matching command handler.
     *
     * @param raw          the unparsed user input (e.g. "send Hello world")
     * @param sessionToken current session token, null if guest
     * @return structured response ready for terminal rendering
     */
    public TerminalResponse dispatch(String raw, String sessionToken) {
        if (raw == null || raw.isBlank()) {
            return TerminalResponse.error("Empty command");
        }

        String commandName = extractCommandName(raw);

        CommandHandler handler = handlers.get(commandName);
        if (handler == null) {
            return new TerminalResponse(
                    ResponseType.ERROR,
                    "Unknown command: " + commandName + " — type /help for available commands",
                    Map.of("suggestions", List.of("Try /help")));
        }

        try {
            Map<String, Object> args = parseArgs(raw, handler);
            return handler.execute(args, sessionToken);
        } catch (Exception e) {
            log.error("Command '{}' failed", commandName, e);
            return new TerminalResponse(
                    ResponseType.ERROR,
                    "Command failed: " + e.getMessage(),
                    Map.of("suggestion", "Usage: " + handler.usage()));
        }
    }

    /**
     * Return metadata for all registered commands (for /help).
     */
    public List<CommandMetadata> listCommands() {
        return handlers.values().stream().map(CommandHandler::metadata).toList();
    }

    /**
     * Expose handler for help generation.
     */
    public CommandHandler getHandler(String name) {
        return handlers.get(name);
    }

    /**
     * All registered handlers for configuration.
     */
    public Collection<CommandHandler> handlers() {
        return handlers.values();
    }

    /**
     * Parse the raw string into an argument map. Splits the input into the command name and treats
     * the rest as a positional "prompt" argument, plus individual tokens as arg1, arg2, ...
     */
    private Map<String, Object> parseArgs(String raw, CommandHandler handler) {
        Map<String, Object> args = new LinkedHashMap<>();
        String[] tokens = raw.split("\\s+");
        if (tokens.length > 1) {
            args.put("prompt", raw.substring(tokens[0].length()).trim());
        }
        for (int i = 1; i < tokens.length; i++) {
            args.put("arg" + i, tokens[i]);
        }
        return args;
    }

    /**
     * Extracts the first word as the command name, stripping leading slash if present.
     */
    private String extractCommandName(String raw) {
        String trimmed = raw.trim();
        String firstWord = trimmed.split("\\s+", 2)[0];
        if (firstWord.startsWith("/")) {
            firstWord = firstWord.substring(1);
        }
        return firstWord.toLowerCase();
    }
}
