package top.focess.veto.mcp;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Represents a Model Context Protocol (MCP) tool definition discovered from an MCP Server. Each
 * tool has a name, description, input schema, and a server association.
 */
public class MCProtocol {

  private final String serverId;
  private final String toolName;
  private final String description;
  private final Map<String, Object> inputSchema;
  private final Map<String, String> annotations;
  private final Instant discoveredAt;
  private volatile MCProtocolStatus status;

  public MCProtocol(
      String serverId,
      String toolName,
      String description,
      Map<String, Object> inputSchema,
      Map<String, String> annotations) {
    this.serverId = serverId;
    this.toolName = toolName;
    this.description = description;
    this.inputSchema = inputSchema;
    this.annotations = annotations;
    this.discoveredAt = Instant.now();
    this.status = MCProtocolStatus.ACTIVE;
  }

  public String getServerId() {
    return serverId;
  }

  public String getToolName() {
    return toolName;
  }

  public String getDescription() {
    return description;
  }

  public Map<String, Object> getInputSchema() {
    return inputSchema;
  }

  public Map<String, String> getAnnotations() {
    return annotations;
  }

  public Instant getDiscoveredAt() {
    return discoveredAt;
  }

  public synchronized MCProtocolStatus getStatus() {
    return status;
  }

  public synchronized void setStatus(MCProtocolStatus status) {
    this.status = status;
  }

  public enum MCProtocolStatus {
    ACTIVE,
    DISABLED,
    ERROR,
    SANDBOXED
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof MCProtocol)) return false;
    MCProtocol that = (MCProtocol) o;
    return serverId.equals(that.serverId) && toolName.equals(that.toolName);
  }

  @Override
  public int hashCode() {
    return Objects.hash(serverId, toolName);
  }

  @Override
  public String toString() {
    return "MCProtocol{server='" + serverId + "', tool='" + toolName + "', status=" + status + "}";
  }
}
