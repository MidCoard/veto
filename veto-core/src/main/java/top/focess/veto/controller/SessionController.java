package top.focess.veto.controller;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import top.focess.veto.agent.SessionAgentRegistry;
import top.focess.veto.agent.TurnRecord;
import top.focess.veto.contract.IpcFrame;
import top.focess.veto.controller.dto.CreateSessionRequest;
import top.focess.veto.i18n.Msg;
import top.focess.veto.llm.core.ToolResultPresentationMode;
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

    public SessionController(
            @NonNull SessionService service,
            @NonNull KeysteadVault vault,
            @NonNull SessionHistoryLoader historyLoader,
            @NonNull SessionRecordService recordService,
            @NonNull SessionAgentRegistry agentRegistry) {
        this.service = service;
        this.vault = vault;
        this.historyLoader = historyLoader;
        this.recordService = recordService;
        this.agentRegistry = agentRegistry;
    }

    @GetMapping
    public @NonNull List<SessionEntity> list() {
        String user = vault.currentUser();
        return user != null ? service.listSessions(user) : List.of();
    }

    /**
     * Creates a session from a pattern, declaring its workspace roots.
     *
     * @param body {@code pattern} and {@code workspaceRoots} (CSV, multi-root) are both required;
     *     {@code name} is optional. {@code currentWorkspaceRootIndex} selects the root used for
     *     relative paths and process execution and defaults to zero.
     */
    @PostMapping
    @SuppressWarnings(
            "JvmTaintAnalysis") // SessionService validates every root as normalized absolute input.
    public @NonNull SessionEntity create(@RequestBody @NonNull CreateSessionRequest body) {
        String user = vault.currentUser();
        if (user == null) throw new IllegalStateException(Msg.get("error.auth.notLoggedIn"));
        String pattern = body.pattern();
        String roots = body.workspaceRoots();
        if (pattern == null || pattern.isBlank() || roots == null || roots.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, Msg.get("error.session.missingFields"));
        }
        Integer rootIndex = body.currentWorkspaceRootIndex();
        return service.createSession(
                user,
                pattern,
                body.name(),
                roots,
                rootIndex == null ? 0 : rootIndex,
                ToolResultPresentationMode.canonicalize(body.toolResultPresentation()),
                Boolean.TRUE.equals(body.guidedEnabled()));
    }

    @DeleteMapping("/{name}")
    public @NonNull ResponseEntity<?> delete(@PathVariable @NonNull String name) {
        String user = vault.currentUser();
        if (user == null) {
            return ResponseEntity.status(401)
                    .body(
                            Map.of(
                                    "status",
                                    "error",
                                    "message",
                                    Msg.get("error.auth.notAuthenticated")));
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
     * tool_name, args}, TOOL_RESPONSE {call_id, content, success}.
     */
    @GetMapping(value = "/{name}/history", produces = MediaType.APPLICATION_JSON_VALUE)
    // Turn payloads intentionally preserve code and model text; Jackson supplies JSON encoding.
    @SuppressWarnings("JvmTaintAnalysis")
    public @NonNull ResponseEntity<?> history(@PathVariable @NonNull String name) {
        SessionConfig cfg = requireOwnedSession(name);
        String owner = vault.currentUser();
        if (owner == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        String agentId = service.primaryAgentIdFor(name, owner).orElse(null);
        List<Map<String, @org.jspecify.annotations.Nullable Object>> turns = new ArrayList<>();
        // The conversation ledger has no agent IDs; keep child streams in /records only.
        if (agentId == null) return ResponseEntity.ok(turns);
        for (TurnRecord turn :
                top.focess.veto.agent.RecordUsage.contentRecords(
                        historyLoader.load(cfg.sessionId(), agentId))) {
            Map<String, @org.jspecify.annotations.Nullable Object> item =
                    new java.util.LinkedHashMap<>();
            item.put("turnNumber", turn.turnNumber());
            item.put("type", turn.type().name());
            item.put("payload", turn.payload());
            item.put("timestamp", turn.timestamp().toString());
            item.put("tokenCount", top.focess.veto.agent.RecordTokenCounter.count(turn.payload()));
            item.put("usedTokens", top.focess.veto.agent.RecordTokenCounter.count(turn.payload()));
            item.put("tokenCountSource", turn.payload().get("tokenCountSource"));
            turns.add(item);
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
                recordService.load(
                        cfg.sessionId(), name, cfg.toolResultPresentation(), cfg.guidedEnabled()));
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

    public record ExecutionState(@NonNull String agentId, boolean busy) {}

    /** Complete session roster, with live state overlaid on durable agent identities. */
    @GetMapping("/{name}/agents")
    public @NonNull List<SessionAgentRegistry.@NonNull AgentSummary> agents(
            @PathVariable @NonNull String name) {
        SessionConfig cfg = requireOwnedSession(name);
        return agentRegistry.records(UUID.fromString(cfg.sessionId()));
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
