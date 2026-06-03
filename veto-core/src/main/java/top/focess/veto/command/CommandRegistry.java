package top.focess.veto.command;

import jakarta.annotation.PostConstruct;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import top.focess.command.*;
import top.focess.veto.contract.ResponseType;
import top.focess.veto.contract.TerminalResponse;

@Component
public class CommandRegistry {

    private static final Logger log = LoggerFactory.getLogger(CommandRegistry.class);
    private static final CommandSender SENDER =
            new CommandSender(CommandPermission.ADMINISTRATOR) {
            };

    private final CommandManager manager = new CommandManager();
    private PromptHandler promptHandler;

    public void setPromptHandler(PromptHandler h) {
        this.promptHandler = h;
    }

    public void register(Command c) {
        manager.register(c);
    }

    @PostConstruct
    public void init() {
        log.info("Registry ready: {} commands", manager.getCommands().size());
    }

    public TerminalResponse dispatch(String raw, String sessionToken, TerminalIO io) {
        if (raw == null || raw.isBlank()) return TerminalResponse.error("Empty input");
        if (!raw.trim().startsWith("/")) {
            if (promptHandler == null) return TerminalResponse.error("Agent not available");
            return promptHandler.handle(raw.trim(), sessionToken);
        }
        String input = raw.trim().substring(1);
        String[] tokens = input.split("\\s+", 2);
        String name = tokens[0].toLowerCase();
        Command cmd = manager.get(name);
        if (cmd == null) return unknown(name);

        String args = tokens.length > 1 ? tokens[1] : "";
        String[] argv = args.isBlank() ? new String[0] : args.trim().split("\\s+");

        try {
            ExecutionResult result = cmd.execute(SENDER, argv, io);
            // REFUSE means "executed but business logic failed" — IOHandler has the response
            // ALLOW means success
            // ARGS means wrong arguments — IOHandler may have usage info
            if (result.getResult() == CommandResult.ARGS_NOT_EXECUTED
                    || result.getResult() == CommandResult.REFUSE_EXCEPTION) {
                return TerminalResponse.error(result.getMessage().orElse("Command failed"));
            }
            TerminalResponse resp = io.getResponse();
            return resp != null ? resp : TerminalResponse.error("No response");
        } catch (Exception e) {
            log.error("Command '{}' failed", name, e);
            return TerminalResponse.error(e.getMessage());
        }
    }

    public List<String> complete(String partial) {
        if (partial == null || partial.isBlank()) return List.of();
        String input = partial.trim();
        if (input.startsWith("/")) input = input.substring(1);
        String[] tokens = input.split("\\s+");
        String name = tokens[0].toLowerCase();
        Command cmd = manager.get(name);
        if (cmd == null) {
            return manager.getCommands().stream()
                    .map(Command::getName)
                    .filter(n -> n.startsWith(name))
                    .map(n -> "/" + n)
                    .toList();
        }
        String[] argv =
                tokens.length > 1 ? Arrays.copyOfRange(tokens, 1, tokens.length) : new String[0];
        return cmd.complete(SENDER, argv);
    }

    private TerminalResponse unknown(String cmd) {
        return new TerminalResponse(
                ResponseType.ERROR,
                "Unknown command: /" + cmd + " — try /help",
                Map.of("suggestions", List.of("Try /help")));
    }

    public Collection<Command> all() {
        return manager.getCommands();
    }
}
