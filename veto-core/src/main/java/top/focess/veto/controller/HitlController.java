package top.focess.veto.controller;

import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import top.focess.veto.agent.AgentService;
import top.focess.veto.agent.intercept.HitlRegistry;
import top.focess.veto.i18n.Msg;
import top.focess.veto.session.SessionService;
import top.focess.veto.vault.KeysteadVault;

/**
 * REST surface for human-in-the-loop vetoes. When the gateway's screening matrix ASKs, the agent
 * parks on a {@link HitlRegistry} future; the terminal path renders a picker over IPC, and this
 * controller gives remote UIs (veto-ui) the same two operations: list the session's pending vetoes,
 * and resolve one with a chosen option name.
 *
 * <p>Resolution is fail-safe: {@link HitlRegistry#resolveOption} validates the option against the
 * set offered at register time and resolves with the scenario's refusal on anything else.
 */
@RestController
@RequestMapping("/api/sessions")
@SuppressWarnings(
        "DuplicatedCode") // Session-scoped controllers repeat the same auth/not-found guard.
public class HitlController {

    private final @NonNull SessionService sessionService;
    private final @NonNull AgentService agentService;
    private final @NonNull HitlRegistry hitlRegistry;
    private final @NonNull KeysteadVault vault;

    public HitlController(
            @NonNull SessionService sessionService,
            @NonNull AgentService agentService,
            @NonNull HitlRegistry hitlRegistry,
            @NonNull KeysteadVault vault) {
        this.sessionService = sessionService;
        this.agentService = agentService;
        this.hitlRegistry = hitlRegistry;
        this.vault = vault;
    }

    /**
     * GET /api/sessions/{name}/vetoes - the pending veto prompts for the session's primary agent:
     * [{callId, toolName, args, options}]. Empty when nothing is parked (or the session has no
     * primary agent yet); 404 when the session does not exist.
     */
    @GetMapping("/{name}/vetoes")
    public @NonNull ResponseEntity<?> pending(@PathVariable @NonNull String name) {
        String agentId = requireAgentId(name);
        return ResponseEntity.ok(hitlRegistry.pendingFor(agentId));
    }

    /**
     * POST /api/sessions/{name}/vetoes/{callId} - resolve a parked veto with {@code {option}}, one
     * of the offered option names. 404 when no veto is parked under that callId (already resolved,
     * or never existed).
     */
    @PostMapping("/{name}/vetoes/{callId}")
    public @NonNull ResponseEntity<?> resolve(
            @PathVariable @NonNull String name,
            @PathVariable @NonNull String callId,
            @RequestBody @NonNull ResolveVetoRequest body) {
        String agentId = requireAgentId(name);
        if (body.option() == null || body.option().isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, Msg.get("error.hitl.optionRequired"));
        }
        if (!agentService.resolveVeto(agentId, callId, body.option())) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, Msg.get("error.hitl.noPendingVeto", callId));
        }
        return ResponseEntity.noContent().build();
    }

    /**
     * POST /api/sessions/{name}/cancel - the REST counterpart of the terminal's cancel: every veto
     * currently parked for the session's primary agent is declined (fail-safe refusal), so the
     * agent unstucks and winds the episode down. A running-but-not-parked episode has no
     * server-side interrupt primitive (matching the terminal, where cancel only detaches the
     * waiting thread) - the client should also abort its in-flight prompt request. Returns the
     * number of vetoes declined.
     */
    @PostMapping("/{name}/cancel")
    public @NonNull ResponseEntity<?> cancel(@PathVariable @NonNull String name) {
        String agentId = requireAgentId(name);
        int declined = agentService.declineAllVetoes(agentId);
        return ResponseEntity.ok(Map.of("status", "ok", "declined", declined));
    }

    /** Resolves the session's primary agent id, enforcing authentication + existence. */
    private @NonNull String requireAgentId(@NonNull String name) {
        String user = vault.currentUser();
        if (user == null) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED, Msg.get("error.auth.notAuthenticated"));
        }
        // A session without a primary agent cannot have parked vetoes - report an empty id space
        // as "session not found" only when the session itself is missing.
        String agentId = sessionService.primaryAgentIdFor(name, user).orElse(null);
        if (agentId == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, Msg.get("error.session.notFound", name));
        }
        return agentId;
    }

    /** Request body for {@link #resolve}. */
    public record ResolveVetoRequest(String option) {}
}
