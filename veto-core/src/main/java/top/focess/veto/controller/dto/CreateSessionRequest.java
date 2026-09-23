package top.focess.veto.controller.dto;

import java.util.List;
import top.focess.veto.api.llm.ToolResultPresentationMode;

public record CreateSessionRequest(
        String pattern,
        String name,
        String workspaceRoots,
        Integer currentWorkspaceRootIndex,
        ToolResultPresentationMode toolResultPresentation,
        List<String> pluginIds) {}
