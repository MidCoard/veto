package top.focess.veto.controller.dto;

/** Request payload for discovering the tools of a remote MCP server by base URL. */
public record DiscoverMcpServerRequest(String baseUrl, String authToken) {}
