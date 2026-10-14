package top.focess.veto.controller;

import org.jspecify.annotations.NonNull;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.focess.veto.agent.intercept.HitlRegistry;
import top.focess.veto.controller.dto.*;
import top.focess.veto.session.SessionService;
import top.focess.veto.vault.KeysteadVault;

/** Authoritative read-only approval snapshots. Interactive decisions use ProtocolConnection. */
@RestController
@RequestMapping("/api/sessions")
public class HitlController {

    private final @NonNull SessionService sessionService;
    private final @NonNull HitlRegistry hitlRegistry;
    private final @NonNull KeysteadVault vault;

    /** Creates the controller with session, agent, HITL-registry, and vault collaborators. */
    public HitlController(
            @NonNull SessionService sessionService,
            @NonNull HitlRegistry hitlRegistry,
            @NonNull KeysteadVault vault) {
        this.sessionService = sessionService;
        this.hitlRegistry = hitlRegistry;
        this.vault = vault;
    }

    /**
     * GET /api/sessions/{name}/vetoes - the pending veto prompts for the session's agents:
     * [{callId, toolName, args, options}]. Empty when nothing is parked (or the session has no
     * primary agent yet); 404 when the session does not exist.
     */
    @GetMapping("/{name}/vetoes")
    public @NonNull ResponseEntity<?> pending(@PathVariable @NonNull String name) {
        String agentId = RequestAuthorization.requireAgentId(name, sessionService, vault);
        return ResponseEntity.ok(
                hitlRegistry.sessionAgents(agentId).stream()
                        .flatMap(id -> hitlRegistry.pendingFor(id).stream())
                        .toList());
    }

    /** Recorded decisions, excluding credential-bearing matching arguments. */
    @GetMapping("/{name}/veto-history")
    public @NonNull ResponseEntity<?> history(@PathVariable @NonNull String name) {
        String agentId = RequestAuthorization.requireAgentId(name, sessionService, vault);
        return ResponseEntity.ok(hitlRegistry.decisions(agentId));
    }
}
