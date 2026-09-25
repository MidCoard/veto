package top.focess.veto.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.*;
import org.jspecify.annotations.*;

/** Payload reporting the tools discovered on an MCP server. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record McpDiscoveryResponse(@NonNull String server, @NonNull List<McpToolResponse> tools)
        implements RestResponse {}
