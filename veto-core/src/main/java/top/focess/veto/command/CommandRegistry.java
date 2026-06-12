package top.focess.veto.command;

import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.focess.command.*;
import top.focess.veto.contract.IpcFrame;
import top.focess.veto.contract.IpcFrame.HintInfo;

/**
 * Registry wrapping the {@link CommandManager} from {@code focess-command}.
 *
 * <p>Provides command registration, dispatch, tab-completion. The {@code ZmqServer} owns the
 * transport and calls {@link #dispatch(VetoCommandSender, String)} with a sender whose outbox has
 * already been wired.
 */
public class CommandRegistry {

    private static final Logger log = LoggerFactory.getLogger(CommandRegistry.class);

    private final CommandManager manager = new CommandManager();

    @Nullable private PromptHandler promptHandler;

    @Nullable private TerminalSessionManager sessionManager;

    public void setPromptHandler(@Nullable PromptHandler h) {
        this.promptHandler = h;
    }

    public void setTerminalSessionManager(@Nullable TerminalSessionManager sm) {
        this.sessionManager = sm;
    }

    public void register(@NotNull Command c) {
        manager.register(c);
    }

    @NotNull
    public List<Command> getCommands() {
        return manager.getCommands();
    }

    @Nullable
    public String resolveUsername(@NotNull String terminalId) {
        return sessionManager != null ? sessionManager.resolve(terminalId) : null;
    }

    // ── dispatch ─────────────────────────────────────────────────────────

    public void dispatch(@NotNull VetoCommandSender sender, @Nullable String raw) {
        if (raw == null || raw.isBlank()) return;

        if (!raw.trim().startsWith("/")) {
            if (promptHandler == null) {
                sender.output("Agent not available.");
                sender.setErrorFlag();
                return;
            }
            promptHandler.handle(raw.trim(), sender.terminalId(), sender);
            return;
        }

        // Guard bare "/" — substring(1) would produce empty input
        if (raw.trim().length() < 2) {
            sender.output("Type /help for available commands.");
            return;
        }

        String input = raw.trim().substring(1).replaceAll("\\s+", " ");
        try {
            ExecutionResult result = manager.dispatch(sender, input);
            CommandResult cr = result.result();
            log.info("Dispatch result for '{}': {}", input, cr);

            if (cr == CommandResult.COMMAND_NOT_FOUND) {
                String cmdName = input.split("\\s+", 2)[0].toLowerCase();
                sender.output("Unknown command: /" + cmdName + " — try /help.");
                sender.setErrorFlag();
            } else if (cr == CommandResult.REFUSE_EXCEPTION) {
                sender.output(result.getMessage().orElse("Command failed."));
                sender.setErrorFlag();
            } else if (cr == CommandResult.REFUSE) {
                sender.setErrorFlag();
            } else if (cr == CommandResult.ARGS_NOT_EXECUTED) {
                // Library already called command.infoUsage(sender) — usage
                // lines were pushed as Delta frames. Nothing more to do.
            }
        } catch (Exception e) {
            log.error("Dispatch failed for {}", sender.terminalId(), e);
            sender.output(e.getMessage() != null ? e.getMessage() : "Command failed.");
            sender.setErrorFlag();
        }
    }

    // ── hint ─────────────────────────────────────────────────────────────

    @NotNull
    public HintInfo hint(@NotNull String terminalId, @Nullable String raw) {
        if (raw == null || raw.isBlank()) return HintInfo.EMPTY;

        String input = raw.stripLeading();
        if (input.startsWith("/")) input = input.substring(1);

        VetoCommandSender sender = new VetoCommandSender(resolveUsername(terminalId), terminalId);

        CommandRoute route = manager.route(sender, input);
        List<CommandArgument<?>> current = route.getCurrentArguments();
        if (current.isEmpty()) return HintInfo.EMPTY;

        // Use the library's own tokenizer — tokenizeToCommandArgs is
        // specifically designed for use with CommandArgument.complete().
        String[] args = CommandManager.tokenizeToCommandArgs(input);

        // For fixed args ("create","list" etc), use complete() to get the
        // literal value since getValue() is package-private in the library.
        List<String> choices =
                current.stream()
                        .filter(CommandArgument::isFixed)
                        .flatMap(a -> a.complete(sender, route.getCommand(), args).stream())
                        .map(CommandCompletion::candidate)
                        .distinct()
                        .toList();
        List<CommandArgument<?>> named =
                current.stream().filter(a -> !a.isFixed()).distinct().toList();

        if (!choices.isEmpty()) {
            String choiceStr = "{" + String.join("|", choices) + "}";
            String desc = null;
            if (!named.isEmpty()) {
                CommandArgument<?> arg = named.get(0);
                String ph =
                        arg.isNullable() ? "[" + arg.getName() + "]" : "<" + arg.getName() + ">";
                choiceStr += " " + ph;
                desc = arg.getDescription();
            }
            return new HintInfo(choiceStr, desc);
        }
        if (!named.isEmpty()) {
            CommandArgument<?> arg = named.get(0);
            String ph = arg.isNullable() ? "[" + arg.getName() + "]" : "<" + arg.getName() + ">";
            return new HintInfo(ph, arg.getDescription());
        }
        return HintInfo.EMPTY;
    }

    // ── completion ───────────────────────────────────────────────────────

    @NotNull
    public List<IpcFrame.Completion> complete(
            @NotNull String terminalId, @Nullable String partial) {
        if (partial == null || partial.isBlank()) return List.of();

        String input = partial.stripLeading();
        if (input.startsWith("/")) input = input.substring(1);

        VetoCommandSender sender = new VetoCommandSender(resolveUsername(terminalId), terminalId);

        List<CommandCompletion> completions = manager.complete(sender, input);

        boolean hasTrailingSpace = !input.equals(input.stripTrailing());
        boolean hasSpaceWithin = input.stripTrailing().contains(" ");
        boolean isSubCommand = hasTrailingSpace || hasSpaceWithin;

        String group = null;
        if (isSubCommand) {
            String cmdName = input.stripTrailing().split("\\s+", 2)[0];
            group =
                    manager.getCommands().stream()
                            .filter(c -> c.getName().equalsIgnoreCase(cmdName))
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
                            return new IpcFrame.Completion(candidate, desc, groupLabel);
                        })
                .toList();
    }

    @NotNull
    public CommandManager manager() {
        return manager;
    }
}
