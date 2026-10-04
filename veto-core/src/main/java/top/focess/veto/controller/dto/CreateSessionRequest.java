package top.focess.veto.controller.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.List;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.llm.ToolResultPresentationMode;

/**
 * Session creation requires a pattern, workspace and explicit plugin selection. An empty plugin
 * list selects no plugins; a null name asks the backend to generate one.
 */
public record CreateSessionRequest(
        @NotBlank @NonNull String pattern,
        String name,
        @NotBlank @NonNull String workspaceRoots,
        @PositiveOrZero Integer currentWorkspaceRootIndex,
        ToolResultPresentationMode toolResultPresentation,
        @JsonProperty(required = true)
                @JsonSetter(nulls = Nulls.FAIL, contentNulls = Nulls.FAIL)
                @NotNull
                @NonNull List<@NotNull @NonNull String> pluginIds) {}
