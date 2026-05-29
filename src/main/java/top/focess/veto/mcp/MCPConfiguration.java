package top.focess.veto.mcp;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Configuration for C4 MCP Extensibility Engine. */
@Configuration
@ConfigurationProperties(prefix = "veto.mcp")
public class MCPConfiguration {

  private String serverRegistryPath = "./mcp/servers/";
  private boolean wasmSandboxEnabled = true;
  private int maxWasmMemoryMb = 64;
  private long discoveryIntervalMs = 60000;

  public String getServerRegistryPath() {
    return serverRegistryPath;
  }

  public void setServerRegistryPath(String serverRegistryPath) {
    this.serverRegistryPath = serverRegistryPath;
  }

  public boolean isWasmSandboxEnabled() {
    return wasmSandboxEnabled;
  }

  public void setWasmSandboxEnabled(boolean wasmSandboxEnabled) {
    this.wasmSandboxEnabled = wasmSandboxEnabled;
  }

  public int getMaxWasmMemoryMb() {
    return maxWasmMemoryMb;
  }

  public void setMaxWasmMemoryMb(int maxWasmMemoryMb) {
    this.maxWasmMemoryMb = maxWasmMemoryMb;
  }

  public long getDiscoveryIntervalMs() {
    return discoveryIntervalMs;
  }

  public void setDiscoveryIntervalMs(long discoveryIntervalMs) {
    this.discoveryIntervalMs = discoveryIntervalMs;
  }
}
