package top.focess.veto.command;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.focess.command.*;
import top.focess.veto.contract.Frame;
import top.focess.veto.contract.Frame.HintInfo;
import top.focess.veto.contract.FrameMeta;
import top.focess.veto.terminal.IpcServer;

/**
 * Registry wrapping the {@link CommandManager} from {@code focess-command}.
 *
 * <p>Provides command registration, dispatch, tab-completion, and hint generation for all Veto
 * slash-commands and plain-text LLM prompts. The {@link IpcServer} owns the transport layer and
 * calls {@link #dispatch(VetoCommandSender, String)} with a sender whose outbox has already been
 * wired to the ROUTER socket.
 *
 * <h3>Thread safety</h3>
 *
 * <p>The underlying {@code CommandManager} is accessed from request-pool virtual threads (one per
 * active command execution). Callers must ensure that individual {@link Command} implementations
 * are themselves thread-safe if concurrent invocations are possible.
 */
public class CommandRegistry {

    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.command.CommandRegistry");

    /** Underlying command manager that handles routing, parsing, and dispatch. */
    private final @NonNull CommandManager manager = new CommandManager();

    /**
     * Optional handler for plain-text (non-slash) LLM prompts. When {@code null}, plain-text input
     * returns an "Agent not available" error.
     */
    private final PromptHandler promptHandler;

    /**
     * Constructs a new {@code CommandRegistry}.
     *
     * @param promptHandler the handler for plain-text LLM prompts, or {@code null} if agent
     *     functionality is not available in this deployment
     */
    public CommandRegistry(PromptHandler promptHandler) {
        this.promptHandler = promptHandler;
    }

    /**
     * Registers a command with the underlying {@link CommandManager}.
     *
     * @param c the command to register; must not be {@code null}
     */
    public void register(@NonNull Command c) {
        manager.register(c);
    }

    /**
     * Returns all commands currently registered with this registry.
     *
     * @return an unmodifiable list of registered commands; never {@code null}
     */
    public @NonNull List<Command> getCommands() {
        return manager.getCommands();
    }

    // ── dispatch ─────────────────────────────────────────────────────────

    /**
     * Dispatches the raw input string as either a slash-command or a plain-text LLM prompt.
     *
     * <p>If the trimmed input is empty, returns a {@link Frame.Done} with empty metadata. If it
     * starts with {@code /}, it is dispatched as a slash-command via {@link CommandManager};
     * otherwise it is forwarded to the {@link PromptHandler} as an LLM prompt.
     *
     * @param sender the command sender for the active terminal session
     * @param raw the raw input string; may be {@code null} or empty
     * @return a {@link Frame.TerminalResponse} ({@link Frame.Done}, {@link Frame.Error}, or {@link
     *     Frame.Terminate}); never {@code null}
     */
    public Frame.@NonNull TerminalResponse dispatch(@NonNull VetoCommandSender sender, String raw) {
        if (raw == null || raw.isEmpty()) {
            return new Frame.Done(Map.of(), null);
        }

        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return new Frame.Done(Map.of(), null);
        }

        if (!trimmed.startsWith("/")) {
            return dispatchAgentPrompt(sender, trimmed);
        } else {
            return dispatchSlashCommand(sender, trimmed);
        }
    }

    private Frame.@NonNull TerminalResponse dispatchAgentPrompt(
            @NonNull VetoCommandSender sender, @NonNull String prompt) {
        if (promptHandler == null) {
            return Frame.Error.ofError("Agent not available.");
        }
        return promptHandler.handle(prompt, sender.terminalId(), sender);
    }

    private Frame.@NonNull TerminalResponse dispatchSlashCommand(
            @NonNull VetoCommandSender sender, @NonNull String commandLine) {

        String input = commandLine.substring(1);
        String commandName = "<unresolved>";
        try {
            var route = manager.route(sender, input);
            var command = route.getCommand();
            commandName = command == null ? "<unknown>" : command.getName();
            ExecutionResult result = route.execute();
            CommandResult cr = result.result();
            log.info("Dispatch result for '{}': {}", commandName, cr);

            if (cr == CommandResult.COMMAND_NOT_FOUND) {
                return Frame.Error.ofError("Unknown command, try /help.");
            } else if (cr == CommandResult.REFUSE_EXCEPTION) {
                Exception exc = result.exception();
                if (exc instanceof TerminateException) {
                    return new Frame.Terminate(((TerminateException) exc).getReason());
                }
                if (exc instanceof LogoutException) {
                    return new Frame.Done(buildDoneMeta(sender, true), null);
                }
                String msg = result.getMessage().orElse("Command failed.");
                return Frame.Error.ofError(msg);
            }

            // Success case
            Map<String, Object> doneMeta = buildDoneMeta(sender, false);
            return new Frame.Done(doneMeta, null);
        } catch (Exception e) {
            // Exception messages and causes can echo command arguments, including secrets.
            log.error(
                    "Dispatch of '{}' failed for {}: {} at {}",
                    commandName,
                    sender.terminalId(),
                    e.getClass().getSimpleName(),
                    Arrays.toString(e.getStackTrace()));
            String msg = e.getMessage() != null ? e.getMessage() : "Command failed.";
            return Frame.Error.ofError(msg);
        }
    }

    private @NonNull Map<String, Object> buildDoneMeta(
            @NonNull VetoCommandSender sender, boolean wasLogout) {
        Map<String, Object> meta = new HashMap<>();
        if (wasLogout) {
            meta.put(FrameMeta.CLEAR_SESSION, true);
        } else if (sender.isLoggedIn()) {
            String username = sender.username();
            if (username != null) {
                meta.put(FrameMeta.USERNAME, username);
            }
            meta.put(FrameMeta.SESSION, sender.terminalId());
            if (promptHandler != null) {
                var agent = promptHandler.activeAgent(sender.terminalId());
                if (agent != null) {
                    meta.put(FrameMeta.TURN_NUMBER, agent.history().size());
                }
            }
        }
        return meta;
    }

    // ── hint ─────────────────────────────────────────────────────────────

    /**
     * Resolves the inline tail-tip hint for the next expected command argument.
     *
     * <p>Routes the current buffer contents through the {@link CommandManager} to determine which
     * arguments come next, then constructs a {@link HintInfo} whose {@link HintInfo#displayText}
     * can be rendered as a JLine tail-tip suggestion.
     *
     * @param sender the command sender for the active terminal session
     * @param raw the current command-line buffer; may be {@code null} or empty
     * @return a {@link HintInfo} describing the next argument, or {@link HintInfo#EMPTY} if no hint
     *     is available; never {@code null}
     */
    public @NonNull HintInfo hint(@NonNull VetoCommandSender sender, String raw) {
        if (raw == null || raw.isEmpty()) return HintInfo.EMPTY;

        String input = raw.stripLeading();
        if (input.startsWith("/")) input = input.substring(1);

        CommandRoute route = manager.route(sender, input);
        Command command = route.getCommand();
        if (command == null) return HintInfo.EMPTY;
        List<CommandArgument<?>> current = route.getCurrentArguments();
        if (current.isEmpty()) return HintInfo.EMPTY;

        String[] args = CommandManager.tokenizeToCommandArgs(input);

        List<String> choices =
                current.stream()
                        .filter(CommandArgument::isFixed)
                        .flatMap(a -> a.complete(sender, command, args).stream())
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

    /**
     * Returns tab-completion candidates for the given partial command-line input.
     *
     * <p>Strips the leading {@code /} (if present) and delegates to {@link
     * CommandManager#route(CommandSender, String)} to compute candidates. Non-slash input returns
     * an empty list — only slash-commands support tab-completion.
     *
     * @param sender the command sender for the active terminal session
     * @param partial the partial command string, including the leading {@code /}; may be {@code
     *     null} or empty
     * @return a list of {@link Frame.Completion} candidates; never {@code null}, may be empty
     */
    public @NonNull List<Frame.Completion> complete(
            @NonNull VetoCommandSender sender, String partial) {
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
                            return new Frame.Completion(candidate, cc.description(), groupLabel);
                        })
                .toList();
    }

    /**
     * Returns the underlying {@link CommandManager} instance.
     *
     * <p>Exposed for callers that need direct access to the manager (e.g. for metrics or
     * introspection). Prefer the higher-level {@link #dispatch}, {@link #complete}, and {@link
     * #hint} methods for normal command processing.
     *
     * @return the {@link CommandManager}; never {@code null}
     */
    public @NonNull CommandManager manager() {
        return manager;
    }
}
