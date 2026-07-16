package top.focess.veto.command.commands;

import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.NonNull;
import top.focess.command.Command;
import top.focess.command.CommandCompletion;
import top.focess.command.CommandResult;
import top.focess.command.CommandSender;
import top.focess.veto.command.PromptHandler;
import top.focess.veto.command.VetoCommand;
import top.focess.veto.command.VetoCommandSender;
import top.focess.veto.model.SessionEntity;
import top.focess.veto.session.LlmConfig;
import top.focess.veto.session.SessionService;

/** Manages the session lifecycle: /session create|list|activate|deactivate|status. */
public class SessionCommand extends VetoCommand {

    private final SessionService service;
    private final PromptHandler promptHandler;

    public SessionCommand(@NonNull SessionService service, @NonNull PromptHandler promptHandler) {
        super("session", "Manage sessions", "ses");
        this.service = service;
        this.promptHandler = promptHandler;
    }

    @Override
    public void init() {
        setExecutorPermission(LOGGED_IN);
        var nameArg = arg("name").completer(this::completeSessionName).description("Session name");

        // /session create <pattern-name> [session-name]
        addExecutor(
                (sender, args) -> {
                    VetoCommandSender s = vetoSender(sender);
                    if (s == null) return CommandResult.REFUSE;
                    String pattern = args.get("pattern");
                    try {
                        SessionEntity session = service.createSession(s.username(), pattern);
                        String requestedName = args.get("name");
                        if (requestedName != null
                                && !requestedName.isEmpty()
                                && !requestedName.equals(pattern)) {
                            session.setName(requestedName);
                        }
                        s.output(
                                "Session '"
                                        + session.getName()
                                        + "' created from pattern '"
                                        + pattern
                                        + "'.");
                        // Auto-activate if no active session on this terminal.
                        if (service.activeSession(s.terminalId()).isEmpty()) {
                            service.activate(s.terminalId(), session.getName(), s.username());
                            s.output("Activated session '" + session.getName() + "'.");
                        }
                        return CommandResult.ALLOW;
                    } catch (IllegalArgumentException e) {
                        s.output(e.getMessage());
                        return CommandResult.REFUSE;
                    }
                },
                fixed("create").description("Create a session from a pattern"),
                arg("pattern").description("Agent pattern name"),
                opt("name").description("Optional session name (defaults to pattern name)"));

        // /session list
        addExecutor(
                (sender, args) -> {
                    VetoCommandSender s = vetoSender(sender);
                    if (s == null) return CommandResult.REFUSE;
                    List<SessionEntity> sessions = service.listSessions(s.username());
                    if (sessions.isEmpty()) {
                        s.output("No sessions. Use /session create <pattern>.");
                        return CommandResult.ALLOW;
                    }
                    s.output("Sessions:");
                    for (SessionEntity se : sessions) {
                        s.output(
                                String.format(
                                        "  %-16s %s",
                                        se.getName(),
                                        se.getLastActiveAt() == null ? "" : se.getLastActiveAt()));
                    }
                    return CommandResult.ALLOW;
                },
                fixed("list").description("List your sessions"));

        // /session activate <name>
        addExecutor(
                (sender, args) -> {
                    VetoCommandSender s = vetoSender(sender);
                    if (s == null) return CommandResult.REFUSE;
                    String n = args.get("name");
                    try {
                        Optional<LlmConfig> cfg = service.activate(s.terminalId(), n, s.username());
                        if (cfg.isEmpty()) {
                            s.output("Session has no primary agent.");
                            return CommandResult.REFUSE;
                        }
                        s.output(
                                "Activated session '"
                                        + n
                                        + "' ("
                                        + cfg.get().provider()
                                        + "/"
                                        + cfg.get().model()
                                        + ").");
                        return CommandResult.ALLOW;
                    } catch (IllegalArgumentException e) {
                        s.output(e.getMessage());
                        return CommandResult.REFUSE;
                    }
                },
                fixed("activate").description("Attach this terminal to an existing session"),
                nameArg);

        // /session deactivate
        addExecutor(
                (sender, args) -> {
                    VetoCommandSender s = vetoSender(sender);
                    if (s == null) return CommandResult.REFUSE;
                    service.deactivate(s.terminalId());
                    s.output("Detached from active session (session persisted).");
                    return CommandResult.ALLOW;
                },
                fixed("deactivate").description("Detach this terminal from its session"));

        // /session status
        addExecutor(
                (sender, args) -> {
                    VetoCommandSender s = vetoSender(sender);
                    if (s == null) return CommandResult.REFUSE;
                    Optional<String> active = service.activeSession(s.terminalId());
                    if (active.isEmpty()) {
                        s.output("No active session on this terminal.");
                        return CommandResult.ALLOW;
                    }
                    Optional<LlmConfig> cfg = service.resolveLlmConfig(s.terminalId());
                    s.output("Active Session:");
                    s.output("  Session id:    " + active.get());
                    cfg.ifPresent(
                            c -> {
                                s.output("  Provider:      " + c.provider());
                                s.output("  Model:         " + c.model());
                            });
                    return CommandResult.ALLOW;
                },
                fixed("status").description("Show the active session details"));
    }

    private @NonNull List<CommandCompletion> completeSessionName(
            @NonNull CommandSender sender, @NonNull Command cmd, @NonNull String[] argv) {
        if (!LOGGED_IN.test(sender)) return List.of();
        String u = ((VetoCommandSender) sender).username();
        String prefix = argv.length > 0 ? argv[argv.length - 1].toLowerCase() : "";
        return service.listSessions(u).stream()
                .filter(se -> se.getName().toLowerCase().startsWith(prefix))
                .map(
                        se ->
                                CommandCompletion.of(
                                        se.getName(),
                                        se.getLastActiveAt() == null
                                                ? ""
                                                : se.getLastActiveAt().toString()))
                .toList();
    }

    @Override
    public @NonNull List<String> usage(@NonNull CommandSender s) {
        return List.of(
                "/session create <pattern> [name] - Create a session from a pattern (auto-activates if idle)",
                "/session list - List your sessions",
                "/session activate <name> - Attach this terminal to a session",
                "/session deactivate - Detach (session persists)",
                "/session status - Show the active session");
    }
}
