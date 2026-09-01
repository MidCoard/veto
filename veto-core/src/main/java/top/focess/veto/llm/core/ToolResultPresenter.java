package top.focess.veto.llm.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.mcp.ToolResult;
import top.focess.veto.agent.mcp.ToolResultFormat;
import top.focess.veto.agent.mcp.ToolResultStatus;

/** Converts a canonical result to the provider message content selected by its session. */
@Component
public class ToolResultPresenter {

    private final @NonNull ObjectMapper mapper;

    public ToolResultPresenter(@NonNull ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public @NonNull String present(@NonNull ToolResult result) {
        return present(result, ToolResultPresentationMode.CONTENT_ONLY);
    }

    public @NonNull String present(
            @NonNull ToolResult result, @NonNull ToolResultPresentationMode mode) {
        if (mode == ToolResultPresentationMode.CONTENT_ONLY) {
            return result.content();
        }
        ObjectNode envelope = mapper.createObjectNode();
        envelope.put("status", result.status().id());
        envelope.put("format", result.format().id());
        envelope.put("content", result.content());
        String errorCode = result.errorCode();
        if (errorCode == null) {
            envelope.putNull("errorCode");
        } else {
            envelope.put("errorCode", errorCode);
        }
        try {
            return mapper.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize tool-result metadata", e);
        }
    }

    public @NonNull String present(
            @NonNull String toolName,
            String callId,
            @NonNull ToolResultStatus status,
            @NonNull ToolResultFormat format,
            @NonNull String content,
            String errorCode,
            @NonNull ToolResultPresentationMode mode) {
        return present(new ToolResult(toolName, callId, status, format, content, errorCode), mode);
    }
}
