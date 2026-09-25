package top.focess.veto.controller.dto;

import java.util.List;
import top.focess.veto.api.llm.ToolResultPresentationMode;

/**
 * Request payload for creating an agent session with optional pattern, workspace, and plugin
 * settings.
 */
public record CreateSessionRequest(
        String pattern,
        String name,
        String workspaceRoots,
        Integer currentWorkspaceRootIndex,
        ToolResultPresentationMode toolResultPresentation,
        List<String> pluginIds) {}
