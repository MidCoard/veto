package top.focess.veto.command;

import jakarta.annotation.PostConstruct;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import top.focess.command.*;
import top.focess.veto.contract.IpcChannel;

/**
 * Registry that wraps the {@link CommandManager} from {@code focess-command} and adapts it for the
 * Veto terminal IPC protocol.
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

    /** Dispatch a request frame. The sender owns all I/O. */
    public void dispatch(
            String terminalId, String raw, IpcChannel reqChannel, IpcChannel respChannel) {
        String username = sessionManager != null ? sessionManager.resolve(terminalId) : null;
        VetoCommandSender sender =
                new VetoCommandSender(username, terminalId, reqChannel, respChannel);

        if (raw == null || raw.isBlank()) {
            sender.error("Empty input");
            return;
        }

        if (!raw.trim().startsWith("/")) {
            if (promptHandler == null) {
                sender.error("Agent not available");
                return;
            }
            promptHandler.handle(raw.trim(), terminalId, sender);
            return;
        }

        String input = raw.trim().substring(1);

        try {
            ExecutionResult result = manager.dispatch(sender, input);
            CommandResult cr = result.result();

            // Only handle the two states the framework declares EXPLICIT
            if (CommandResult.EXPLICIT.contains(cr)) {
                if (cr == CommandResult.COMMAND_NOT_FOUND) {
                    String cmdName = input.split("\\s+", 2)[0].toLowerCase();
                    sender.error("Unknown: /" + cmdName + " — try /help");
                } else {
                    sender.error(result.getMessage().orElse("Command failed"));
                }
                return;
            }

            if (!sender.hasResponded()) {
                sender.done(Map.of());
            }
        } catch (Exception e) {
            log.error("Dispatch failed for {}", terminalId, e);
            if (!sender.hasResponded()) {
                sender.error(e.getMessage());
            }
        }
    }

    /**
     * Handle a hint frame. Uses {@link CommandRoute#getCurrentArguments} to find the expected
     * argument at the cursor position. The trailing space (or lack thereof) tells the framework
     * where the user's cursor is — do NOT strip it.
     */
    public String[] hint(String terminalId, String raw) {
        if (raw == null || raw.isBlank()) return new String[] {null, null};

        String input = raw.stripLeading();
        if (input.startsWith("/")) input = input.substring(1);

        String username = sessionManager != null ? sessionManager.resolve(terminalId) : null;
        VetoCommandSender sender = new VetoCommandSender(username, terminalId, null, null);

        CommandRoute route = manager.route(sender, input);
        List<CommandArgument<?>> current = route.getCurrentArguments();
        if (current.isEmpty()) return new String[] {null, null};

        CommandArgument<?> arg = current.get(0);
        String name = arg.getName();
        if (name == null) return new String[] {null, null};

        String placeholder = arg.isNullable() ? "[" + name + "]" : "<" + name + ">";
        String desc = arg.getDescription();
        return new String[] {placeholder, desc};
    }

    /**
     * Handle a completion frame. Returns tab-separated {@code candidate[\tdescription[\tgroup]]}
     * lines so the terminal can create rich JLine {@code Candidate} objects.
     */
    public List<String> complete(String terminalId, String partial) {
        if (partial == null || partial.isBlank()) return List.of();

        String input = partial.stripLeading();
        if (input.startsWith("/")) input = input.substring(1);

        String username = sessionManager != null ? sessionManager.resolve(terminalId) : null;
        VetoCommandSender sender = new VetoCommandSender(username, null, null, null);

        List<CommandCompletion> completions = manager.complete(sender, input);

        boolean hasTrailingSpace = !input.equals(input.stripTrailing());
        boolean hasSpaceWithin = input.stripTrailing().contains(" ");
        boolean isSubCommand = hasTrailingSpace || hasSpaceWithin;

        String group = null;
        if (isSubCommand) {
            String cmdName = input.stripTrailing().split("\\s+", 2)[0];
            group =
                    manager.getCommands().stream()
                            .filter(c -> c.getName().equals(cmdName))
                            .findFirst()
                            .map(Command::getDescription)
                            .orElse(null);
        }

        final String groupLabel = group;
        return completions.stream()
                .map(
                        cc -> {
                            String candidate = cc.candidate();
                            String desc = cc.description();
                            if (!hasTrailingSpace && !hasSpaceWithin) {
                                candidate = "/" + candidate;
                            }
                            StringBuilder sb = new StringBuilder(candidate);
                            sb.append('\t');
                            if (desc != null && !desc.isBlank()) {
                                sb.append(desc);
                            }
                            if (groupLabel != null && !groupLabel.isBlank()) {
                                sb.append('\t').append(groupLabel);
                            }
                            return sb.toString();
                        })
                .toList();
    }
}
