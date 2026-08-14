package top.focess.veto.controller;

import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.focess.veto.agent.AgentRunner;
import top.focess.veto.i18n.Msg;
import top.focess.veto.llm.core.LlmOptions;
import top.focess.veto.session.SessionService;
import top.focess.veto.session.SessionService.SessionConfig;
import top.focess.veto.vault.KeysteadVault;

/**
 * REST prompt submission endpoint — the command half of the transport rule (commands -> REST POST,
 * events -> WebSocket, authoritative reads -> REST GET). The episode starts and this call acks
 * immediately with 202 + the session id; progress (thoughts, tool calls/results, HITL vetoes) and
 * the episode outcome arrive as {@code DeltaFrame} events on the WS bus, and {@code GET
 * /api/sessions/{name}/history} is the durable source of truth. A blocking collect-and-return call
 * would cap episode length at the HTTP timeout — HITL pauses alone outlast any such cap.
 */
@RestController
@RequestMapping("/api/sessions")
public class PromptController {

    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.controller.PromptController");

    private final @NonNull SessionService sessionService;
    private final top.focess.veto.agent.@NonNull AgentService agentService;
    private final @NonNull KeysteadVault vault;

    PromptController(
            @NonNull SessionService sessionService,
            top.focess.veto.agent.@NonNull AgentService agentService,
            @NonNull KeysteadVault vault) {
        this.sessionService = sessionService;
        this.agentService = agentService;
        this.vault = vault;
    }

    /**
     * POST /api/sessions/{name}/prompt - submit a prompt to the agent bound to the named session.
     * The caller must be authenticated (the SecurityContextInterceptor sets the vault's currentUser
     * from the X-Veto-Session-Token header). Returns 202 as soon as the episode is enqueued.
     */
    @PostMapping(
            value = "/{name}/prompt",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    // Responses are Jackson-serialized JSON, never an HTML rendering context.
    //noinspection tainting
    public ResponseEntity<?> prompt(
            @PathVariable @NonNull String name, @RequestBody @NonNull PromptRequest body) {
        String user = vault.currentUser();
        if (user == null) {
            return ResponseEntity.status(401)
                    .body(Map.of("error", Msg.get("error.auth.notAuthenticated")));
        }
        String prompt = body.prompt();
        if (prompt == null || prompt.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", Msg.get("error.prompt.empty")));
        }

        // activateForRest (not resolveByName): the agent must be the session-aware one - persona
        // id = the DB primary agent id (so parked HITL vetoes are visible to HitlController) and
        // workspace = the session's roots (so tools run against them, not the JVM working dir).
        SessionConfig cfg = sessionService.activateForRest(name, user).orElse(null);
        if (cfg == null) {
            return ResponseEntity.status(404)
                    .body(Map.of("error", Msg.get("error.session.notFoundForUser", name, user)));
        }

        AgentRunner.LlmBinding binding =
                new AgentRunner.LlmBinding(
                        cfg.config().provider(),
                        cfg.config().model(),
                        cfg.config().credKey(),
                        LlmOptions.defaults(),
                        null,
                        cfg.config().baseUrl());

        try {
            agentService.submitNow(cfg.sessionId(), prompt, binding);
            log.info("Prompt accepted for session {} (agent {})", name, cfg.sessionId());
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body(Map.of("status", "started", "sessionId", cfg.sessionId()));
        } catch (Exception e) {
            log.warn("Prompt submit failed for session {}", name, e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", Msg.get("error.prompt.failed")));
        }
    }

    /** Request body for {@link #prompt}. */
    public record PromptRequest(String prompt) {}
}
