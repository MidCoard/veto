package top.focess.veto.agent.capability;

import java.io.IOException;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.tool.ToolCapability;
import top.focess.veto.extension.contract.StandardExtensionPoints;
import top.focess.veto.extension.contract.TextProtection;
import top.focess.veto.plugin.runtime.SessionPlugins;
import top.focess.veto.secret.references.SecretCandidateStore;

/** File capture bound to the screened native file read and its owned session. */
@Component
public final class ProtectedWorkspaceReadCapabilityImpl implements WorkspaceReadCapability {
    private final @NonNull SecretCandidateStore candidates;
    private final @Nullable SessionPlugins plugins;

    @Autowired
    public ProtectedWorkspaceReadCapabilityImpl(
            @NonNull SecretCandidateStore candidates,
            @NonNull ObjectProvider<SessionPlugins> plugins) {
        this.candidates = candidates;
        this.plugins = plugins.getIfAvailable();
    }

    public ProtectedWorkspaceReadCapabilityImpl(@NonNull SecretCandidateStore candidates) {
        this.candidates = candidates;
        this.plugins = null;
    }

    @Override
    public @NonNull WorkspaceFile file(@NonNull String path) throws IOException {
        var context = CapabilityAccess.require(ToolCapability.WORKSPACE_READ);
        return new ReadOnlyWorkspaceFile(
                WorkspaceFileAccess.resolve(context.executionPermit(), path, false));
    }

    @Override
    public @NonNull String captureFileText(@NonNull String input) {
        var context = CapabilityAccess.require(ToolCapability.WORKSPACE_READ, "view_file");
        String owner = context.owner();
        UUID session = context.sessionId();
        if (owner == null || owner.isBlank() || session == null)
            throw new IllegalStateException(
                    "Protected file reading requires an active owned session");
        if (plugins != null)
            return plugins.protect(
                    StandardExtensionPoints.FILE_PROTECTION,
                    new TextProtection.Scope(owner, session.toString(), context.agentId()),
                    input);
        return candidates
                .captureFile(
                        new SecretCandidateStore.Scope(
                                owner, session.toString(), context.agentId()),
                        UUID.randomUUID().toString(),
                        input)
                .text();
    }
}
