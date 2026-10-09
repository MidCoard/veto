package top.focess.veto.command;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import top.focess.command.CommandManager;
import top.focess.veto.agent.Agent;
import top.focess.veto.agent.AgentService;
import top.focess.veto.api.agent.AgentResult;
import top.focess.veto.bus.DeltaBroker;
import top.focess.veto.bus.DeltaFrame;
import top.focess.veto.i18n.Msg;
import top.focess.veto.session.SessionService;

/**
 * Selected-session operations exposed to the web UI; account and terminal commands stay separate.
 */
@Service
public class SessionCommandService {
    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.command.SessionCommandService");
    private static final @NonNull Set<@NonNull String> COMMANDS = Set.of("compact");
    private final @NonNull SessionService sessions;
    private final @NonNull AgentService agents;

    private final @NonNull DeltaBroker broker;

    public SessionCommandService(
            @NonNull SessionService sessions,
            @NonNull AgentService agents,
            @NonNull DeltaBroker broker) {
        this.sessions = sessions;
        this.agents = agents;
        this.broker = broker;
    }

    /** Backend command recognition, independent of terminal and account command registration. */
    public boolean supports(@NonNull String input) {
        var commandLine = input.strip();
        if (!commandLine.startsWith("/")) return false;
        var tokens = CommandManager.tokenizeToArgs(commandLine.substring(1));
        return tokens.length > 0 && COMMANDS.contains(tokens[0]);
    }

    /** Enqueues a recognized operation; completion is delivered by the normal agent bus. */
    public @NonNull String enqueue(@NonNull String name, @NonNull UUID owner) {
        var session =
                sessions.activateForRest(name, owner).orElseThrow(SessionNotFoundException::new);
        var agent = agents.agentsView().get(session.sessionId());
        if (agent == null) throw new IllegalStateException("Session agent is unavailable");
        var request = agent.compact();
        log.debug(
                "Compaction queued for session {} (request {})",
                session.sessionId(),
                request.requestId());
        return session.sessionId();
    }

    /** Plain presentation feedback, never a model-context or account-command operation. */
    public void notice(@NonNull String sessionId, String agentId, @NonNull String prompt) {
        if (!prompt.strip().startsWith("/")) return;
        publishNotice(sessionId, agentId, Msg.get("input.command.asPrompt"));
    }

    public void primaryNotice(@NonNull String sessionId, @NonNull String agentId) {
        publishNotice(sessionId, agentId, Msg.get("input.command.primaryCompaction"));
    }

    private void publishNotice(@NonNull String sessionId, String agentId, @NonNull String text) {
        var frame =
                DeltaFrame.builder()
                        .sessionId(UUID.fromString(sessionId))
                        .kind(DeltaFrame.Kind.NOTICE)
                        .text(text);
        if (agentId != null) frame.attr("agentId", agentId);
        broker.publish(frame.build());
    }

    /** Terminal compaction wait; web input returns as soon as execution is queued. */
    public @NonNull AgentResult compact(@NonNull Agent agent)
            throws TimeoutException, InterruptedException {
        return agent.compact().await(Duration.ofMinutes(2));
    }

    public static final class SessionNotFoundException extends IllegalArgumentException {
        public SessionNotFoundException() {
            super("Session not found");
        }
    }
}
