package top.focess.veto.controller;

import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import top.focess.veto.agent.AgentState;
import top.focess.veto.agent.SessionAgentRegistry;
import top.focess.veto.controller.dto.SubmitPromptRequest;
import top.focess.veto.session.SessionService;
import top.focess.veto.vault.KeysteadVault;

/** Direct user interaction with an explicitly enabled live agent in an owned session. */
@RestController
public class AgentPromptController {
    private final @NonNull SessionService sessions;
    private final @NonNull SessionAgentRegistry agents;
    private final @NonNull KeysteadVault vault;

    public AgentPromptController(
            @NonNull SessionService sessions,
            @NonNull SessionAgentRegistry agents,
            @NonNull KeysteadVault vault) {
        this.sessions = sessions;
        this.agents = agents;
        this.vault = vault;
    }

    @PostMapping("/api/sessions/{name}/agents/{agentId}/prompt")
    public @NonNull ResponseEntity<?> prompt(
            @PathVariable @NonNull String name,
            @PathVariable @NonNull String agentId,
            @RequestBody @NonNull SubmitPromptRequest body) {
        String user = vault.currentUser();
        if (user == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        var session =
                sessions.resolveByName(name, user)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        String prompt = body.prompt();
        if (prompt == null || prompt.isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "Prompt must not be empty"));
        var agent =
                agents.agents(UUID.fromString(session.sessionId())).stream()
                        .filter(entry -> entry.agent().id().equals(agentId))
                        .findFirst()
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND))
                        .agent();
        if (!agent.userInteractionEnabled())
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Direct user interaction is disabled for this agent"));
        if (agent.state() == AgentState.TERMINATED)
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "Agent has terminated"));
        try {
            agent.submitUserPrompt(prompt);
        } catch (IllegalStateException error) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "Agent is no longer available"));
        }
        return ResponseEntity.accepted()
                .body(
                        Map.of(
                                "status",
                                "queued",
                                "sessionId",
                                session.sessionId(),
                                "agentId",
                                agentId));
    }
}
