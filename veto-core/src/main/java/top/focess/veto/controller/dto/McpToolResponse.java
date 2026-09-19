package top.focess.veto.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.*;
import org.jspecify.annotations.*;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record McpToolResponse(
        @NonNull String name,
        @NonNull String description,
        @NonNull String capability,
        @NonNull String defaultDanger,
        com.fasterxml.jackson.databind.@NonNull JsonNode inputSchema)
        implements RestResponse {}
