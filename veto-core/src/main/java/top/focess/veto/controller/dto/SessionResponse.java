package top.focess.veto.controller.dto;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.llm.ToolResultPresentationMode;
import top.focess.veto.api.plugin.PluginBinding;
import top.focess.veto.model.SessionEntity;

/** Session metadata with workspace paths rendered for the authenticated client. */
public record SessionResponse(
        @NonNull String id,
        @NonNull String owner,
        @NonNull String name,
        String workspaceRoots,
        int currentWorkspaceRootIndex,
        String primaryAgentId,
        @NonNull ToolResultPresentationMode toolResultPresentation,
        @NonNull Instant createdAt,
        Instant lastActiveAt,
        List<PluginBinding> pluginBindings) {

    /** Copies public session metadata without exposing persistence-owned workspace paths. */
    public static @NonNull SessionResponse from(
            @NonNull SessionEntity session, String workspaceRoots) {
        return new SessionResponse(
                session.getId(),
                session.getOwner(),
                session.getName(),
                workspaceRoots,
                session.getCurrentWorkspaceRootIndex(),
                session.getPrimaryAgentId(),
                session.getToolResultPresentation(),
                session.getCreatedAt(),
                session.getLastActiveAt(),
                session.getPluginBindings());
    }
}
