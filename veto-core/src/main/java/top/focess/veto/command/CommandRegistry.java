package top.focess.veto.command;

import java.util.List;
import java.util.Map;
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

    private final @Nullable PromptHandler promptHandler;

    public CommandRegistry(@Nullable PromptHandler promptHandler) {
        this.promptHandler = promptHandler;
    }

    public void register(@NotNull Command c) {
        manager.register(c);
    }

    @NotNull
    public List<Command> getCommands() {
        return manager.getCommands();
    }

    // ── dispatch ─────────────────────────────────────────────────────────

    @Nullable
    public IpcFrame.TerminalResponse dispatch(
            @NotNull VetoCommandSender sender, @Nullable String raw) {
        if (raw == null || raw.isEmpty()) {
            return new IpcFrame.Done(Map.of(), null);
        }

        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return new IpcFrame.Done(Map.of(), null);
        }

        if (!trimmed.startsWith("/")) {
            return dispatchAgentPrompt(sender, trimmed);
        } else {
            return dispatchSlashCommand(sender, trimmed);
        }
    }

    @NotNull
    private IpcFrame.TerminalResponse dispatchAgentPrompt(
            @NotNull VetoCommandSender sender, @NotNull String prompt) {
        if (promptHandler == null) {
            return IpcFrame.Error.ofError("Agent not available.");
        }
        return promptHandler.handle(prompt, sender.terminalId(), sender);
    }

    private IpcFrame.TerminalResponse dispatchSlashCommand(
            @NotNull VetoCommandSender sender, @NotNull String commandLine) {

        String input = commandLine.substring(1);
        try {
            ExecutionResult result = manager.dispatch(sender, input);
            CommandResult cr = result.result();
            log.info("Dispatch result for '{}': {}", input, cr);

            if (cr == CommandResult.COMMAND_NOT_FOUND) {
                return IpcFrame.Error.ofError("Unknown command, try /help.");
            } else if (cr == CommandResult.REFUSE_EXCEPTION) {
                Exception exc = result.exception();
                if (exc instanceof TerminateException) {
                    return new IpcFrame.Terminate(((TerminateException) exc).getReason());
                }
                if (exc instanceof LogoutException) {
                    return new IpcFrame.Done(buildDoneMeta(sender, true), null);
                }
                String msg = result.getMessage().orElse("Command failed.");
                return IpcFrame.Error.ofError(msg);
            }

            // Success case
            Map<String, Object> doneMeta = buildDoneMeta(sender, false);
            return new IpcFrame.Done(doneMeta, null);
        } catch (Exception e) {
            log.error("Dispatch failed for {}", sender.terminalId(), e);
            String msg = e.getMessage() != null ? e.getMessage() : "Command failed.";
            return IpcFrame.Error.ofError(msg);
        }
    }

    private Map<String, Object> buildDoneMeta(
            @NotNull VetoCommandSender sender, boolean wasLogout) {
        Map<String, Object> meta = new java.util.HashMap<>();
        if (wasLogout) {
            meta.put(top.focess.veto.contract.IpcMeta.CLEAR_SESSION, true);
        } else if (sender.isLoggedIn()) {
            meta.put(top.focess.veto.contract.IpcMeta.USERNAME, sender.username());
            meta.put(top.focess.veto.contract.IpcMeta.SESSION, sender.terminalId());
            if (promptHandler != null) {
                var agent = promptHandler.sessions().get(sender.terminalId());
                if (agent != null) {
                    meta.put(top.focess.veto.contract.IpcMeta.TURN_NUMBER, agent.turns().size());
                }
            }
        }
        return meta;
    }

    // ── hint ─────────────────────────────────────────────────────────────

    @NotNull
    public HintInfo hint(@NotNull VetoCommandSender sender, @Nullable String raw) {
        if (raw == null || raw.isEmpty()) return HintInfo.EMPTY;

        String input = raw.stripLeading();
        if (input.startsWith("/")) input = input.substring(1);

        CommandRoute route = manager.route(sender, input);
        if (route.getCommand() == null) return HintInfo.EMPTY;
        List<CommandArgument<?>> current = route.getCurrentArguments();
        if (current.isEmpty()) return HintInfo.EMPTY;

        String[] args = CommandManager.tokenizeToCommandArgs(input);

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
                CommandArgument<?> arg = named.getFirst();
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
            @NotNull VetoCommandSender sender, @Nullable String partial) {
        if (partial == null || partial.isEmpty()) return List.of();

        String input = partial.stripLeading();
        if (input.startsWith("/")) {
            input = input.substring(1);
        }

        CommandRoute route = manager.route(sender, input);
        List<CommandCompletion> completions = route.getCompletions();
        Command cmd = route.getCommand();
        String groupLabel = cmd != null ? cmd.getDescription() : null;

        boolean hasSpace = input.contains(" ");

        return completions.stream()
                .map(
                        cc -> {
                            String candidate = cc.candidate();
                            if (!hasSpace) {
                                candidate = "/" + candidate;
                            }
                            return new IpcFrame.Completion(candidate, cc.description(), groupLabel);
                        })
                .toList();
    }

    @NotNull
    public CommandManager manager() {
        return manager;
    }
}
