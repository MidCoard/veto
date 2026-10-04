package top.focess.veto.controller;

import jakarta.validation.Valid;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import top.focess.veto.agent.RecordTokenCounter;
import top.focess.veto.agent.RecordUsage;
import top.focess.veto.agent.SessionAgentRegistry;
import top.focess.veto.agent.TurnRecord;
import top.focess.veto.agent.workspace.WorkspaceAdmissionPolicy;
import top.focess.veto.api.llm.ToolResultPresentationMode;
import top.focess.veto.contract.IpcFrame;
import top.focess.veto.controller.dto.*;
import top.focess.veto.i18n.Msg;
import top.focess.veto.integration.plugins.SessionPlugins;
import top.focess.veto.model.SessionEntity;
import top.focess.veto.session.SessionHistoryLoader;
import top.focess.veto.session.SessionRecordService;
import top.focess.veto.session.SessionService;
import top.focess.veto.session.SessionService.SessionConfig;
import top.focess.veto.vault.KeysteadVault;

/**
 * REST facade over {@link SessionService} for remote UIs (veto-ui).
 *
 * <p>Unlike the terminal path - which maps the terminal's cwd to the workspace via the {@link
 * IpcFrame.Hello} handshake - a remote UI has no cwd to report, so it declares the workspace roots
 * explicitly in the create request body. The owner is the authenticated vault user;
 * activation/attachment is a UI concern (the UI holds the returned session id and submits prompts
 * through its own transport), so this controller only manages the session lifecycle, not prompt
 * dispatch.
 */
@RestController
@RequestMapping("/api/sessions")
public class SessionController {

    private final @NonNull SessionService service;
    private final @NonNull KeysteadVault vault;
    private final @NonNull SessionHistoryLoader historyLoader;
    private final @NonNull SessionRecordService recordService;
    private final @NonNull SessionAgentRegistry agentRegistry;
    private final @NonNull SessionPlugins sessionPlugins;
    private final @NonNull WorkspaceAdmissionPolicy workspaceAdmission;

    /** Creates the controller with session, vault, history, record, and agent-registry services. */
    public SessionController(
            @NonNull SessionService service,
            @NonNull KeysteadVault vault,
            @NonNull SessionHistoryLoader historyLoader,
            @NonNull SessionRecordService recordService,
            @NonNull SessionAgentRegistry agentRegistry,
            @NonNull SessionPlugins sessionPlugins,
            @NonNull WorkspaceAdmissionPolicy workspaceAdmission) {
        this.service = service;
        this.vault = vault;
        this.historyLoader = historyLoader;
        this.recordService = recordService;
        this.agentRegistry = agentRegistry;
        this.sessionPlugins = sessionPlugins;
        this.workspaceAdmission = workspaceAdmission;
    }

    /** Lists the current user's sessions; empty when not logged in. */
    @GetMapping
    public @NonNull List<SessionResponse> list() {
        String user = vault.currentUser();
        if (user == null) return List.of();
        return service.listSessions(user).stream().map(session -> response(user, session)).toList();
    }

    /**
     * Creates a session from a pattern, declaring its workspace roots.
     *
     * @param body {@code pattern} and {@code workspaceRoots} (a nonempty list) are both required;
     *     {@code pluginIds} is required and may be empty; {@code name} is optional. {@code
     *     currentWorkspaceRootIndex} selects the root used for relative paths and process execution
     *     and defaults to zero.
     */
    @PostMapping
    @SuppressWarnings(
            "JvmTaintAnalysis") // SessionService validates every root as normalized absolute input.
    public @NonNull SessionResponse create(@RequestBody @Valid @NonNull CreateSessionRequest body) {
        String user = vault.currentUser();
        if (user == null) throw new IllegalStateException(Msg.get("error.auth.notLoggedIn"));
        String pattern = body.pattern();
        var roots = body.workspaceRoots();
        Integer rootIndex = body.currentWorkspaceRootIndex();
        var created =
                service.createSession(
                        user,
                        pattern,
                        body.name(),
                        roots,
                        rootIndex == null ? 0 : rootIndex,
                        ToolResultPresentationMode.canonicalize(body.toolResultPresentation()),
                        body.pluginIds());
        return response(user, created);
    }

    private @NonNull SessionResponse response(
            @NonNull String owner, @NonNull SessionEntity session) {
        var roots = session.getWorkspaceRoots();
        String rendered = null;
        if (roots != null && !roots.isBlank()) {
            try {
                rendered =
                        Arrays.stream(roots.split(","))
                                .map(String::trim)
                                .map(root -> workspaceAdmission.toClientPath(owner, Path.of(root)))
                                .collect(Collectors.joining(","));
            } catch (IllegalArgumentException | IllegalStateException unavailable) {
                // Historic roots outside current policy have no client-visible representation.
            }
        }
        return SessionResponse.from(session, rendered);
    }

    /** Deletes an owned session by name; 404 when the user has no such session. */
    @DeleteMapping("/{name}")
    public @NonNull ResponseEntity<?> delete(@PathVariable @NonNull String name) {
        String user = vault.currentUser();
        if (user == null) {
            return ResponseEntity.status(401)
                    .body(
                            new StatusMessageResponse(
                                    "error", Msg.get("error.auth.notAuthenticated")));
        }
        if (!service.delete(user, name)) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, Msg.get("error.session.notFound", name));
        }
        return ResponseEntity.noContent().build();
    }

    /**
     * GET /api/sessions/{name}/history - the session's durable turn log (the {@code turn_records}
     * table), ordered by turn number. Same {turnNumber, type, payload} shape as the history array
     * in the prompt response, but available for past/inactive sessions too. Payload contents vary
     * by turn type (see {@link TurnRecord} factories): USER_PROMPT {content}, ASSISTANT_THOUGHT
     * {response} (raw veto_pulse JSON string), ASSISTANT_RESPONSE {content}, TOOL_CALL {call_id,
     * tool_name, args, native_state?, model_call_id?}, TOOL_RESPONSE {call_id, content, success,
     * status, format, presentation, llmUsage?, errorCode?, approval?}.
     */
    @GetMapping(value = "/{name}/history", produces = MediaType.APPLICATION_JSON_VALUE)
    // Turn payloads intentionally preserve code and model text; Jackson supplies JSON encoding.
    @SuppressWarnings("JvmTaintAnalysis")
    public @NonNull ResponseEntity<?> history(@PathVariable @NonNull String name) {
        SessionConfig cfg = requireOwnedSession(name);
        String owner = vault.currentUser();
        if (owner == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        String agentId = service.primaryAgentIdFor(name, owner).orElse(null);
        List<HistoryTurnResponse> turns = new ArrayList<>();
        // The conversation ledger has no agent IDs; keep child streams in /records only.
        if (agentId == null) return ResponseEntity.ok(turns);
        for (TurnRecord turn :
                RecordUsage.contentRecords(historyLoader.load(cfg.sessionId(), agentId))) {
            Long count = RecordTokenCounter.count(turn.payload());
            Object source = turn.payload().get("tokenCountSource");
            turns.add(
                    new HistoryTurnResponse(
                            turn.turnNumber(),
                            turn.type().name(),
                            turn.payload(),
                            turn.timestamp().toString(),
                            count,
                            count,
                            source instanceof String value ? value : null,
                            turn.llmUsage()));
        }
        return ResponseEntity.ok(turns);
    }

    /**
     * The complete records view for veto-ui. It retains the append-only trace while annotating each
     * agent stream with the effective state produced by its REWIND directives.
     */
    @GetMapping(value = "/{name}/records", produces = MediaType.APPLICATION_JSON_VALUE)
    public @NonNull ResponseEntity<?> records(@PathVariable @NonNull String name) {
        SessionConfig cfg = requireOwnedSession(name);
        return ResponseEntity.ok(
                recordService.load(cfg.sessionId(), name, cfg.toolResultPresentation()));
    }

    /**
     * Authoritative live execution state for reconnecting clients; absent agents are not running.
     */
    @GetMapping("/{name}/execution")
    public @NonNull List<ExecutionState> execution(@PathVariable @NonNull String name) {
        SessionConfig cfg = requireOwnedSession(name);
        return agentRegistry.agents(UUID.fromString(cfg.sessionId())).stream()
                .map(
                        entry ->
                                new ExecutionState(
                                        entry.agent().id(), entry.agent().hasPendingWork()))
                .toList();
    }

    /** Live busy/idle state per running agent of an owned session. */
    public record ExecutionState(@NonNull String agentId, boolean busy) {}

    /** Complete session roster, with live state overlaid on durable agent identities. */
    @GetMapping("/{name}/agents")
    public @NonNull List<SessionAgentRegistry.@NonNull AgentSummary> agents(
            @PathVariable @NonNull String name) {
        SessionConfig cfg = requireOwnedSession(name);
        return agentRegistry.records(UUID.fromString(cfg.sessionId()));
    }

    /** Shows preserved plugin pins, including packages missing after a backend restart. */
    @GetMapping("/{name}/plugins")
    public @NonNull List<SessionPlugins.BoundPluginStatus> plugins(
            @PathVariable @NonNull String name) {
        SessionConfig cfg = requireOwnedSession(name);
        return sessionPlugins.status(cfg.sessionId());
    }

    private @NonNull SessionConfig requireOwnedSession(@NonNull String name) {
        String user = vault.currentUser();
        if (user == null) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED, Msg.get("error.auth.notAuthenticated"));
        }
        return service.resolveByName(name, user)
                .orElseThrow(
                        () ->
                                new ResponseStatusException(
                                        HttpStatus.NOT_FOUND,
                                        Msg.get("error.session.notFoundGeneric")));
    }
}
