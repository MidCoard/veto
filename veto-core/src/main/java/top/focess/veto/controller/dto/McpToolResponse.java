package top.focess.veto.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import org.jspecify.annotations.*;

/** Payload describing a single tool exposed by an MCP server, including its input schema. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record McpToolResponse(
        @NonNull String name,
        @NonNull String description,
        @NonNull String capability,
        @NonNull String defaultDanger,
        @NonNull JsonNode inputSchema)
        implements RestResponse {}
