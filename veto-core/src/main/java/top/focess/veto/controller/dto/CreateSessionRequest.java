package top.focess.veto.controller.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import java.util.List;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.llm.ToolResultPresentationMode;

/**
 * Session creation requires a pattern, workspace and explicit plugin selection. An empty plugin
 * list selects no plugins; a null name asks the backend to generate one.
 */
public record CreateSessionRequest(
        String pattern,
        String name,
        String workspaceRoots,
        Integer currentWorkspaceRootIndex,
        ToolResultPresentationMode toolResultPresentation,
        @JsonProperty(required = true) @JsonSetter(nulls = Nulls.FAIL, contentNulls = Nulls.FAIL)
                @NonNull List<@NonNull String> pluginIds) {}
