package top.focess.veto.command.commands;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import top.focess.command.Command;
import top.focess.command.CommandCompletion;
import top.focess.command.CommandResult;
import top.focess.command.CommandSender;
import top.focess.veto.agent.TurnType;
import top.focess.veto.agent.workspace.WorkspaceAdmissionPolicy;
import top.focess.veto.api.llm.ToolResultPresentationMode;
import top.focess.veto.command.VetoCommand;
import top.focess.veto.command.VetoCommandSender;
import top.focess.veto.model.SessionEntity;
import top.focess.veto.security.HostPathInput;
import top.focess.veto.session.LlmConfig;
import top.focess.veto.session.SessionService;

/** Manages sessions and reads their conversation history through {@code /session}. */
public class SessionCommand extends VetoCommand {

    private final @NonNull SessionService service;
    private final @NonNull WorkspaceAdmissionPolicy workspaceAdmission;

    /** Constructs the {@code /session} command over the given session service. */
    public SessionCommand(
            @NonNull SessionService service, @NonNull WorkspaceAdmissionPolicy workspaceAdmission) {
        super("session", "Manage sessions", "ses");
        this.service = service;
        this.workspaceAdmission = workspaceAdmission;
    }

    @Override
    public void init() {
        setExecutorPermission(LOGGED_IN);
        var nameArg = arg("name").completer(this::completeSessionName).description("Session name");

        addExecutor(
                (sender, args) -> {
                    var terminal = vetoSender(sender);
                    if (terminal == null) return CommandResult.REFUSE;
                    var id = UUID.fromString(requiredArg(args.get("id"), "id"));
                    if (service.resumeSession(
                                    terminal.terminalId(),
                                    id,
                                    terminal.requireUserId(),
                                    terminal.cwd())
                            .isEmpty())
                        throw new IllegalArgumentException("Session has no primary agent");
                    return CommandResult.ALLOW;
                },
                fixed("resume").description("Restore an owned session by ID in this workspace"),
                arg("id").description("Session ID"));

        // /session create <pattern-name> [session-name] [plugin-id,...]
        addExecutor(
                (sender, args) -> {
                    VetoCommandSender s = vetoSender(sender);
                    if (s == null) return CommandResult.REFUSE;
                    String pattern = requiredArg(args.get("pattern"), "pattern");
                    String requestedName = args.get("name");
                    try {
                        String selection = args.get("plugins");
                        List<@NonNull String> pluginIds =
                                selection == null
                                        ? List.of()
                                        : Arrays.stream(selection.split(",", -1))
                                                .map(String::trim)
                                                .toList();
                        if (pluginIds.stream().anyMatch(String::isEmpty))
                            throw new IllegalArgumentException(
                                    "Plugin IDs must be a comma-separated list without empty entries.");
                        var userId = s.requireUserId();
                        var cwd =
                                HostPathInput.canonicalForCreation(
                                        HostPathInput.absoluteNormalized(s.cwd(), "terminal cwd"),
                                        "terminal cwd");
                        var workspace = workspaceAdmission.toClientPath(userId, cwd);
                        SessionEntity session =
                                service.createSession(
                                        userId,
                                        pattern,
                                        requestedName,
                                        List.of(workspace),
                                        0,
                                        ToolResultPresentationMode.BASIC,
                                        pluginIds);
                        s.output(
                                "Session '"
                                        + session.getName()
                                        + "' created from pattern '"
                                        + pattern
                                        + "'.");
                        // Auto-activate if no active session on this terminal. The session was
                        // just bound to the terminal's cwd, so the strict workspace check inside
                        // activate() trivially passes.
                        if (service.activeSession(s.terminalId()).isEmpty()) {
                            service.activate(
                                    s.terminalId(), session.getName(), s.requireUserId(), s.cwd());
                            s.output("Activated session '" + session.getName() + "'.");
                        }
                        return CommandResult.ALLOW;
                    } catch (IllegalArgumentException | IllegalStateException e) {
                        s.output(e.getMessage());
                        return CommandResult.REFUSE;
                    }
                },
                fixed("create").description("Create a session from a pattern"),
                arg("pattern").description("Agent pattern name"),
                opt("name").description("Optional session name (defaults to pattern name)"),
                opt("plugins")
                        .description("Comma-separated plugin IDs selected for this new session"));

        // /session list
        addExecutor(
                (sender, args) -> {
                    VetoCommandSender s = vetoSender(sender);
                    if (s == null) return CommandResult.REFUSE;
                    List<SessionEntity> sessions = service.listSessions(s.requireUserId(), s.cwd());
                    if (sessions.isEmpty()) {
                        s.output(
                                "No sessions in this workspace ('"
                                        + s.cwd()
                                        + "'). Use /session create <pattern>.");
                        return CommandResult.ALLOW;
                    }
                    s.output("Sessions in this workspace:");
                    for (SessionEntity se : sessions) {
                        s.output(
                                String.format(
                                        "  %-16s %s",
                                        se.getName(),
                                        se.getLastActiveAt() == null ? "" : se.getLastActiveAt()));
                    }
                    return CommandResult.ALLOW;
                },
                fixed("list").description("List your sessions in the current workspace"));

        // /session activate <name>
        addExecutor(
                (sender, args) -> {
                    VetoCommandSender s = vetoSender(sender);
                    if (s == null) return CommandResult.REFUSE;
                    String n = requiredArg(args.get("name"), "name");
                    try {
                        Optional<LlmConfig> cfg =
                                service.activate(s.terminalId(), n, s.requireUserId(), s.cwd());
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
                        s.output(
                                "Use /session history <name> to read earlier conversation records.");
                        return CommandResult.ALLOW;
                    } catch (IllegalArgumentException e) {
                        s.output(e.getMessage());
                        return CommandResult.REFUSE;
                    }
                },
                fixed("activate").description("Attach this terminal to an existing session"),
                nameArg);

        addExecutor(
                (sender, args) -> {
                    VetoCommandSender s = vetoSender(sender);
                    if (s == null) return CommandResult.REFUSE;
                    return showHistory(s, requiredArg(args.get("name"), "name"), args.get("limit"));
                },
                fixed("history").description("Read recent records without activating a session"),
                nameArg,
                opt("limit").description("Record count, 1–200 (default 40)"));

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

    private @NonNull CommandResult showHistory(
            @NonNull VetoCommandSender sender, @NonNull String name, String requestedLimit) {
        int limit;
        try {
            limit = requestedLimit == null ? 40 : Integer.parseInt(requestedLimit);
        } catch (NumberFormatException invalid) {
            sender.output("History limit must be an integer between 1 and 200.");
            return CommandResult.REFUSE;
        }
        if (limit < 1 || limit > 200) {
            sender.output("History limit must be an integer between 1 and 200.");
            return CommandResult.REFUSE;
        }
        try {
            var records =
                    service.history(name, sender.requireUserId(), sender.cwd()).stream()
                            .filter(
                                    turn ->
                                            turn.type() != TurnType.AGENT_INIT
                                                    && turn.type() != TurnType.TOKEN_USAGE)
                            .filter(
                                    turn ->
                                            turn.type() != TurnType.REWIND
                                                    || turn.payload().get("content")
                                                                    instanceof String text
                                                            && !text.isBlank())
                            .toList();
            if (records.isEmpty()) {
                sender.output("No conversation history in session '" + name + "'.");
                return CommandResult.ALLOW;
            }
            int start = Math.max(0, records.size() - limit);
            sender.output(
                    "Conversation history: "
                            + (records.size() - start)
                            + " of "
                            + records.size()
                            + " effective records (primary agent).");
            for (var turn : records.subList(start, records.size())) {
                Object content =
                        switch (turn.type()) {
                            case USER_INTERRUPT -> turn.payload().get("feedback");
                            case ASSISTANT_THOUGHT -> turn.payload().get("response");
                            case TOOL_CALL ->
                                    turn.payload().get("tool_name")
                                            + " "
                                            + turn.payload().get("args");
                            default -> turn.payload().get("content");
                        };
                String text = content == null ? "" : content.toString();
                if (text.length() > 4_000) {
                    int end = Character.isHighSurrogate(text.charAt(3_999)) ? 3_999 : 4_000;
                    text =
                            text.substring(0, end)
                                    + "\n[Preview truncated; open this session's Web Records for the full content.]";
                }
                sender.output("[" + turn.type() + " #" + turn.turnNumber() + "]\n" + text);
            }
            return CommandResult.ALLOW;
        } catch (IllegalArgumentException | IllegalStateException unavailable) {
            sender.output(unavailable.getMessage());
            return CommandResult.REFUSE;
        }
    }

    private @NonNull List<CommandCompletion> completeSessionName(
            @NonNull CommandSender sender, @NonNull Command cmd, @NonNull String @NonNull [] argv) {
        if (!LOGGED_IN.test(sender)) return List.of();
        VetoCommandSender v = (VetoCommandSender) sender;
        UUID u = v.requireUserId();
        String prefix = argv.length > 0 ? argv[argv.length - 1].toLowerCase() : "";
        return service.listSessions(u, v.cwd()).stream()
                .filter(se -> se.getName().toLowerCase().startsWith(prefix))
                .map(
                        se -> {
                            Instant lastActive = se.getLastActiveAt();
                            return CommandCompletion.of(
                                    se.getName(), lastActive == null ? "" : lastActive.toString());
                        })
                .toList();
    }

    @Override
    public @NonNull List<String> usage(@NonNull CommandSender s) {
        return List.of(
                "/session create <pattern> [name] [plugin-id,...] - Create with explicitly selected plugins (auto-activates if idle)",
                "/session list - List your sessions in the current workspace",
                "/session activate <name> - Attach this terminal to a session in this workspace",
                "/session resume <id> - Restore an owned session in this workspace",
                "/session deactivate - Detach (session persists)",
                "/session history <name> [limit] - Read recent primary-agent records (default 40, max 200)",
                "/session status - Show the active session");
    }
}
