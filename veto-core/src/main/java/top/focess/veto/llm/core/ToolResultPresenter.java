package top.focess.veto.llm.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import top.focess.veto.api.agent.tool.ToolErrorCode;
import top.focess.veto.api.agent.tool.ToolResult;
import top.focess.veto.api.agent.tool.ToolResultFormat;
import top.focess.veto.api.agent.tool.ToolResultStatus;
import top.focess.veto.api.llm.ToolResultPresentationMode;

/** Converts a canonical result to the provider message content selected by its session. */
@Component
public class ToolResultPresenter {

    private final @NonNull ObjectMapper mapper;

    /** Creates the presenter with the shared JSON mapper. */
    public ToolResultPresenter(@NonNull ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** Presents a result in {@link ToolResultPresentationMode#BASIC} mode. */
    public @NonNull String present(@NonNull ToolResult result) {
        return present(result, ToolResultPresentationMode.BASIC);
    }

    /**
     * Presents a result as plain content in basic mode, or as a JSON envelope carrying status,
     * format, content, and error code in a detailed mode.
     */
    public @NonNull String present(
            @NonNull ToolResult result, @NonNull ToolResultPresentationMode mode) {
        if (!mode.detailed()) {
            return result.content();
        }
        ObjectNode envelope = mapper.createObjectNode();
        envelope.put("status", result.status().id());
        envelope.put("format", result.format().id());
        envelope.put("content", result.content());
        ToolErrorCode errorCode = result.errorCode();
        if (errorCode == null) {
            envelope.putNull("errorCode");
        } else {
            envelope.put("errorCode", errorCode.name());
        }
        try {
            return mapper.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize tool-result metadata", e);
        }
    }

    /** Presents ad-hoc result fields, equivalent to building the {@link ToolResult} first. */
    public @NonNull String present(
            @NonNull String toolName,
            String callId,
            @NonNull ToolResultStatus status,
            @NonNull ToolResultFormat format,
            @NonNull String content,
            ToolErrorCode errorCode,
            @NonNull ToolResultPresentationMode mode) {
        return present(new ToolResult(toolName, callId, status, format, content, errorCode), mode);
    }
}
