package top.focess.veto.controller;

import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import top.focess.veto.agent.SessionAgentRegistry;
import top.focess.veto.session.SessionService;
import top.focess.veto.vault.UserContext;

/** Explicit user pause controls; resuming never resolves an approval or replays a tool. */
@RestController
public class AgentControlController {
    private final @NonNull SessionService sessions;
    private final @NonNull SessionAgentRegistry agents;

    public AgentControlController(
            @NonNull SessionService sessions, @NonNull SessionAgentRegistry agents) {
        this.sessions = sessions;
        this.agents = agents;
    }

    @PostMapping("/api/sessions/{name}/agents/{agentId}/control/{operation}")
    public @NonNull Map<String, Object> control(
            @PathVariable @NonNull String name,
            @PathVariable @NonNull String agentId,
            @PathVariable @NonNull String operation) {
        String owner = UserContext.get();
        if (owner == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if (!operation.equals("pause") && !operation.equals("resume"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        var session =
                sessions.resolveByName(name, owner)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        try {
            agents.controlPause(
                    UUID.fromString(session.sessionId()), agentId, operation.equals("pause"));
        } catch (IllegalArgumentException unavailable) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        } catch (IllegalStateException unavailable) {
            throw new ResponseStatusException(HttpStatus.CONFLICT);
        }
        return Map.of("agentId", agentId, "userPaused", operation.equals("pause"));
    }
}
