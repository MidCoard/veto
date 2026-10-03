package top.focess.veto.agent.capability;

import java.io.IOException;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import top.focess.veto.api.agent.capability.WorkspaceFile;
import top.focess.veto.api.agent.capability.WorkspaceReadCapability;
import top.focess.veto.api.agent.tool.ToolCapability;
import top.focess.veto.api.event.BeforeTextCommitEvent;
import top.focess.veto.api.plugin.Scope;
import top.focess.veto.event.EventManager;

/** File capture bound to the screened native file read and its owned session. */
@Component
public final class ProtectedWorkspaceReadCapabilityImpl implements WorkspaceReadCapability {
    private final EventManager events;

    /** Spring construction: uses the host event manager when one is available. */
    @Autowired
    public ProtectedWorkspaceReadCapabilityImpl(@NonNull ObjectProvider<EventManager> events) {
        this.events = events.getIfAvailable();
    }

    /** Detached construction (tests): capture falls back to the unchanged text. */
    public ProtectedWorkspaceReadCapabilityImpl() {
        this.events = null;
    }

    /** Host event delivery binds protection to the pinned selection used for this invocation. */
    public ProtectedWorkspaceReadCapabilityImpl(EventManager events) {
        this.events = events;
    }

    @Override
    public @NonNull WorkspaceFile file(@NonNull String path) throws IOException {
        var context = CapabilityAccess.require(ToolCapability.WORKSPACE_READ);
        return new ReadOnlyWorkspaceFile(
                WorkspaceFileAccess.resolve(context.executionPermit(), path, false));
    }

    @Override
    public @NonNull String captureFileText(@NonNull String input) {
        var context = CapabilityAccess.require(ToolCapability.WORKSPACE_READ);
        String owner = context.owner();
        UUID session = context.sessionId();
        if (owner == null || owner.isBlank() || session == null)
            throw new IllegalStateException(
                    "Protected file reading requires an active owned session");
        if (events != null) {
            var event =
                    new BeforeTextCommitEvent(
                            new Scope.AgentScope(owner, session.toString(), context.agentId()),
                            BeforeTextCommitEvent.Phase.FILE_CAPTURE,
                            UUID.randomUUID().toString(),
                            input);
            events.submit(event);
            if (event.isCancelled()) throw new IllegalStateException("File capture cancelled");
            return event.text();
        }
        return input;
    }
}
