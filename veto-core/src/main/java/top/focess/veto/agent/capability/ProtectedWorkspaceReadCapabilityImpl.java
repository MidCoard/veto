package top.focess.veto.agent.capability;

import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

import top.focess.veto.agent.tool.ToolCapability;
import top.focess.veto.secret.references.SecretCandidateStore;

import java.io.IOException;
import java.util.UUID;

/** File capture bound to the screened native file read and its owned session. */
@Component
public final class ProtectedWorkspaceReadCapabilityImpl implements WorkspaceReadCapability {
    private final @org.jspecify.annotations.Nullable SecretCandidateStore candidates;
    private final top.focess.veto.plugin.runtime.@org.jspecify.annotations.Nullable SessionPlugins
            plugins;

    @org.springframework.beans.factory.annotation.Autowired
    public ProtectedWorkspaceReadCapabilityImpl(
            top.focess.veto.plugin.runtime.@NonNull SessionPlugins plugins) {
        this.plugins = plugins;
        this.candidates = null;
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
                    top.focess.veto.extension.contract.StandardExtensionPoints.FILE_PROTECTION,
                    new top.focess.veto.extension.contract.TextProtection.Scope(
                            owner, session.toString(), context.agentId()),
                    input);
        if (candidates == null) throw new IllegalStateException("File protection unavailable");
        return candidates
                .captureFile(
                        new SecretCandidateStore.Scope(
                                owner, session.toString(), context.agentId()),
                        UUID.randomUUID().toString(),
                        input)
                .text();
    }
}
