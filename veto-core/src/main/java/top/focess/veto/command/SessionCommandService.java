package top.focess.veto.command;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import top.focess.command.CommandManager;
import top.focess.veto.api.plugin.Scope;
import top.focess.veto.contract.Delta;
import top.focess.veto.i18n.Msg;
import top.focess.veto.session.AgentSessions;
import top.focess.veto.session.SessionService;

/** Session command recognition and presentation shared by connection input paths. */
@Service
public class SessionCommandService {
    private static final @NonNull Set<@NonNull String> COMMANDS = Set.of("compact");
    private final @NonNull SessionService sessions;
    private final @NonNull AgentSessions runtime;

    public SessionCommandService(@NonNull SessionService sessions, @NonNull AgentSessions runtime) {
        this.sessions = sessions;
        this.runtime = runtime;
    }

    /** Backend command recognition, independent of terminal and account command registration. */
    public boolean supports(@NonNull String input) {
        var commandLine = input.strip();
        if (!commandLine.startsWith("/")) return false;
        var tokens = CommandManager.tokenizeToArgs(commandLine.substring(1));
        return tokens.length > 0 && COMMANDS.contains(tokens[0]);
    }

    /** Plain presentation feedback, never a model-context or account-command operation. */
    public void notice(
            @NonNull UUID userId,
            @NonNull String sessionId,
            String agentId,
            @NonNull String prompt,
            @NonNull Locale locale) {
        if (!prompt.strip().startsWith("/")) return;
        publishNotice(userId, sessionId, agentId, Msg.get(locale, "input.command.asPrompt"));
    }

    public void primaryNotice(
            @NonNull UUID userId,
            @NonNull String sessionId,
            @NonNull String agentId,
            @NonNull Locale locale) {
        publishNotice(
                userId, sessionId, agentId, Msg.get(locale, "input.command.primaryCompaction"));
    }

    private void publishNotice(
            @NonNull UUID userId, @NonNull String sessionId, String agentId, @NonNull String text) {
        var sessionScope = new Scope.SessionScope(userId, sessionId);
        Scope scope =
                agentId == null ? sessionScope : new Scope.AgentScope(userId, sessionId, agentId);
        runtime.session(sessionScope)
                .send(new Delta(scope, new Delta.Notice(text, Delta.Level.INFO)));
    }

    /** The connection follows the request handle and emits its final reply after settlement. */
    public void compact(@NonNull VetoCommandSender sender) {
        var id =
                sessions.activeSession(sender.terminalId())
                        .orElseThrow(SessionNotFoundException::new);
        var user = sender.requireUserId();
        var session = sessions.runtimeSession(id, user);
        var agent = session.primary(user);
        var connection = sender.connection();
        var locale = connection.locale();
        connection.submitAgent(
                session,
                new Scope.AgentScope(user, id, agent.id()),
                admission -> session.compact(user, locale, admission));
    }

    /** Requested session could not be found by the command service. */
    private static final class SessionNotFoundException extends IllegalArgumentException {
        private SessionNotFoundException() {
            super("Session not found");
        }
    }
}
