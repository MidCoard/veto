package top.focess.veto.controller;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.focess.veto.agent.AgentResult;
import top.focess.veto.agent.AgentRunner;
import top.focess.veto.agent.VetoAgent;
import top.focess.veto.i18n.Msg;
import top.focess.veto.llm.core.LlmOptions;
import top.focess.veto.session.SessionService;
import top.focess.veto.session.SessionService.SessionConfig;
import top.focess.veto.vault.KeysteadVault;

/**
 * REST prompt submission endpoint. The IPC (terminal) path streams Deltas/ToolCalls/ToolResults
 * over ZMQ; this controller exposes the same agent dispatch over a synchronous HTTP call so a
 * non-terminal client (curl, a web UI, a test script) can submit a prompt and get the full response
 * (messages, thoughts, tool calls, tool results) in one JSON body.
 *
 * <p>The call blocks until the agent episode finishes (or times out). No streaming.
 */
@RestController
@RequestMapping("/api/sessions")
public class PromptController {

    private static final Logger log = LoggerFactory.getLogger(PromptController.class);
    private static final Duration EPISODE_TIMEOUT = Duration.ofMinutes(5);

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
     * from the X-Veto-Session-Token header).
     */
    @PostMapping("/{name}/prompt")
    public ResponseEntity<?> prompt(
            @PathVariable @NonNull String name, @RequestBody @NonNull PromptRequest body) {
        String user = vault.currentUser();
        if (user == null) {
            return ResponseEntity.status(401)
                    .body(Map.of("error", Msg.get("error.auth.notAuthenticated")));
        }
        if (body.prompt() == null || body.prompt().isBlank()) {
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

        // Collect all streamed artifacts so the response carries the full interaction.
        List<String> messages = new CopyOnWriteArrayList<>();
        List<String> thoughts = new CopyOnWriteArrayList<>();
        List<Map<String, Object>> toolCalls = new CopyOnWriteArrayList<>();
        List<Map<String, Object>> toolResults = new CopyOnWriteArrayList<>();

        try {
            AgentResult result =
                    agentService.submit(
                            cfg.sessionId(),
                            body.prompt(),
                            binding,
                            EPISODE_TIMEOUT,
                            messages::add,
                            null, // no veto sink (REST can't render a picker)
                            thoughts::add,
                            call ->
                                    toolCalls.add(
                                            Map.of(
                                                    "toolName",
                                                    call.toolName(),
                                                    "args",
                                                    call.args())),
                            tr ->
                                    toolResults.add(
                                            Map.of("body", tr.body(), "success", tr.success())));

            // Also grab the full turn history so the caller can verify what the model saw.
            List<Map<String, Object>> history = new ArrayList<>();
            VetoAgent agent = agentService.agent(cfg.sessionId());
            if (agent != null) {
                for (var turn : agent.history()) {
                    history.add(
                            Map.of(
                                    "turnNumber",
                                    turn.turnNumber(),
                                    "type",
                                    turn.type().name(),
                                    "payload",
                                    turn.payload()));
                }
            }

            return ResponseEntity.ok(
                    Map.of(
                            "success",
                            result.success(),
                            "message",
                            result.message() != null ? result.message() : "",
                            "messages",
                            messages,
                            "thoughts",
                            thoughts,
                            "toolCalls",
                            toolCalls,
                            "toolResults",
                            toolResults,
                            "history",
                            history));
        } catch (Exception e) {
            log.warn("Prompt failed for session {}", name, e);
            // e.getMessage() is null for bare TimeoutException/NPE - Map.of rejects null values,
            // which would mask the real failure behind another NPE. A TimeoutException is the
            // 5-minute episode deadline hit; anything else gets the generic keyed message with
            // the raw detail as the parameter.
            if (e instanceof java.util.concurrent.TimeoutException) {
                return ResponseEntity.internalServerError()
                        .body(Map.of("error", Msg.get("error.agent.episodeTimeout")));
            }
            String message = e.getMessage();
            return ResponseEntity.internalServerError()
                    .body(
                            Map.of(
                                    "error",
                                    Msg.get(
                                            "error.prompt.failed",
                                            message != null
                                                    ? message
                                                    : e.getClass().getSimpleName())));
        }
    }

    /** Request body for {@link #prompt}. */
    public record PromptRequest(@Nullable String prompt) {}
}
