package top.focess.veto.controller;

import java.net.URI;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import top.focess.veto.agent.mcp.McpTransport;
import top.focess.veto.agent.mcp.RemoteToolDefinition;
import top.focess.veto.agent.mcp.ToolEngineImpl;
import top.focess.veto.vault.KeysteadVault;
import top.focess.veto.vault.UserRegistry;

/** Admin-only registration of external MCP servers and their discovered tool schemas. */
@RestController
@RequestMapping("/api/mcp/servers")
public class McpServerController {

    private final @NonNull ToolEngineImpl toolEngine;
    private final @NonNull KeysteadVault vault;
    private final @NonNull UserRegistry users;

    public McpServerController(
            @NonNull ToolEngineImpl toolEngine,
            @NonNull KeysteadVault vault,
            @NonNull UserRegistry users) {
        this.toolEngine = toolEngine;
        this.vault = vault;
        this.users = users;
    }

    /** Discover and register the tools exposed by an HTTP/SSE MCP endpoint. */
    @PostMapping("/discover")
    public @NonNull Map<String, Object> discover(@RequestBody @NonNull DiscoverRequest request) {
        requireAdmin();
        URI endpoint = validatedEndpoint(request.baseUrl());
        String authToken = request.authToken() == null ? "" : request.authToken();
        List<RemoteToolDefinition> tools =
                toolEngine.discoverAndRegister(
                        new McpTransport.SseMcpTransport(endpoint.toString(), authToken));
        if (tools.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY, "MCP server returned no discoverable tools");
        }
        return Map.of(
                "server", endpoint.toString(),
                "tools", tools.stream().map(McpServerController::toolView).toList());
    }

    private void requireAdmin() {
        String username = vault.currentUser();
        if (username == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        if (!users.isAdmin(username)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Administrator role required");
        }
    }

    private static @NonNull URI validatedEndpoint(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "baseUrl is required");
        }
        URI endpoint;
        try {
            endpoint = URI.create(raw);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "baseUrl is not a valid URI");
        }
        String scheme = endpoint.getScheme();
        if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                || endpoint.getHost() == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "baseUrl must be an absolute HTTP(S) URI");
        }
        return endpoint;
    }

    private static @NonNull Map<String, Object> toolView(@NonNull RemoteToolDefinition tool) {
        return Map.of(
                "name", tool.name(),
                "description", tool.description(),
                "risk", tool.risk().name(),
                "inputSchema", tool.inputSchema());
    }

    public record DiscoverRequest(String baseUrl, String authToken) {}
}
