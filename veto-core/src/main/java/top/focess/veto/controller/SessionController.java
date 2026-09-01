package top.focess.veto.controller;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import top.focess.veto.agent.TurnRecord;
import top.focess.veto.i18n.Msg;
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
 * top.focess.veto.contract.IpcFrame.Hello} handshake - a remote UI has no cwd to report, so it
 * declares the workspace roots explicitly in the create request body. The owner is the
 * authenticated vault user; activation/attachment is a UI concern (the UI holds the returned
 * session id and submits prompts through its own transport), so this controller only manages the
 * session lifecycle, not prompt dispatch.
 */
@RestController
@RequestMapping("/api/sessions")
public class SessionController {

    private final @NonNull SessionService service;
    private final @NonNull KeysteadVault vault;
    private final @NonNull SessionHistoryLoader historyLoader;
    private final @NonNull SessionRecordService recordService;

    public SessionController(
            @NonNull SessionService service,
            @NonNull KeysteadVault vault,
            @NonNull SessionHistoryLoader historyLoader,
            @NonNull SessionRecordService recordService) {
        this.service = service;
        this.vault = vault;
        this.historyLoader = historyLoader;
        this.recordService = recordService;
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
     *     {@code name} is optional. A remote UI has no cwd to report, so it must name the workspace
     *     roots explicitly - the request is rejected with 400 when either is missing/blank.
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
        return service.createSession(
                user,
                pattern,
                body.name(),
                roots,
                top.focess.veto.llm.core.ToolResultPresentationMode.canonicalize(
                        body.toolResultPresentation()));
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
        String user = vault.currentUser();
        if (user == null) {
            return ResponseEntity.status(401)
                    .body(Map.of("error", Msg.get("error.auth.notAuthenticated")));
        }
        SessionConfig cfg = service.resolveByName(name, user).orElse(null);
        if (cfg == null) {
            return ResponseEntity.status(404)
                    .body(Map.of("error", Msg.get("error.session.notFoundForUser", name, user)));
        }
        List<Map<String, Object>> turns = new ArrayList<>();
        for (TurnRecord turn : historyLoader.load(cfg.sessionId())) {
            turns.add(
                    Map.of(
                            "turnNumber",
                            turn.turnNumber(),
                            "type",
                            turn.type().name(),
                            "payload",
                            turn.payload(),
                            "timestamp",
                            turn.timestamp().toString()));
        }
        return ResponseEntity.ok(turns);
    }

    /**
     * The complete records view for veto-ui. It retains the append-only trace while annotating each
     * agent stream with the effective state produced by its REWIND directives.
     */
    @GetMapping(value = "/{name}/records", produces = MediaType.APPLICATION_JSON_VALUE)
    public @NonNull ResponseEntity<?> records(@PathVariable @NonNull String name) {
        String user = vault.currentUser();
        if (user == null) {
            return ResponseEntity.status(401)
                    .body(Map.of("error", Msg.get("error.auth.notAuthenticated")));
        }
        SessionConfig cfg = service.resolveByName(name, user).orElse(null);
        if (cfg == null) {
            return ResponseEntity.status(404)
                    .body(Map.of("error", Msg.get("error.session.notFoundForUser", name, user)));
        }
        return ResponseEntity.ok(
                recordService.load(cfg.sessionId(), name, cfg.toolResultPresentation()));
    }

    /** Request body for {@link #create}. */
    public record CreateSessionRequest(
            String pattern,
            String name,
            String workspaceRoots,
            top.focess.veto.llm.core.ToolResultPresentationMode toolResultPresentation) {}
}
