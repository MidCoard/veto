package top.focess.veto.integration.plugins;

import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import top.focess.veto.api.event.AgentTerminatedEvent;
import top.focess.veto.api.event.SessionDeletedEvent;
import top.focess.veto.api.event.UserLoggedInEvent;
import top.focess.veto.api.event.UserLogoutEvent;
import top.focess.veto.api.event.UserRegisteredEvent;
import top.focess.veto.api.plugin.Scope;

/**
 * Dispatches best-effort runtime transition facts. Runtime notifications isolate nonfatal listener
 * failures; fatal VM errors and thread death propagate. Required transactional deletion preparation
 * belongs to {@link PluginDataCleanup}.
 *
 * <p>Notifications run inline on authentication/logout callers, committed-deletion transaction
 * callbacks, or agent termination callers. Each call creates its own event and completes its
 * prepared recipient route before returning. Separate callers may dispatch concurrently; this
 * service owns neither a notification queue nor global transition ordering.
 */
@Service
public class PluginLifecycleEvents {
    private final @NonNull PluginManager manager;

    /** Creates the dispatcher over the installed plugin catalog. */
    public PluginLifecycleEvents(@NonNull PluginManager manager) {
        this.manager = manager;
    }

    /** Best-effort notification after signup created and authenticated a user. */
    public void userRegistered(@NonNull String ownerId) {
        manager.events().broadcast(new UserRegisteredEvent(new Scope.UserScope(ownerId)));
    }

    /** Best-effort notification after an existing user logged in. */
    public void userLoggedIn(@NonNull String ownerId) {
        manager.events().broadcast(new UserLoggedInEvent(new Scope.UserScope(ownerId)));
    }

    /** Best-effort notification when unified user logout begins. */
    public void userLogout(@NonNull String ownerId) {
        manager.events().broadcast(new UserLogoutEvent(new Scope.UserScope(ownerId)));
    }

    /** Best-effort notification after session deletion commits. */
    public void sessionDeleted(@NonNull String ownerId, @NonNull String sessionId) {
        manager.events()
                .broadcast(new SessionDeletedEvent(new Scope.SessionScope(ownerId, sessionId)));
    }

    /** Best-effort notification that a session agent terminated. */
    public void agentTerminated(
            @NonNull String ownerId, @NonNull String sessionId, @NonNull String agentId) {
        manager.events()
                .broadcast(
                        new AgentTerminatedEvent(
                                new Scope.AgentScope(ownerId, sessionId, agentId)));
    }
}
