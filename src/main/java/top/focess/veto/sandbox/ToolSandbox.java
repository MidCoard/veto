package top.focess.veto.sandbox;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import top.focess.veto.model.ToolExecutionRequest;
import top.focess.veto.orchestrator.SwarmOrchestrator;

/**
 * C6 Atomic Tool Execution Sandbox - top-level service. Executes physical OS operations through
 * strictly predefined atomic capabilities. No generic command injection (run_bash) is ever allowed.
 * Integrates with C5 SwarmOrchestrator for execution and C8 CredentialVault for secret injection.
 */
@Service
public class ToolSandbox {

  private static final Logger log = LoggerFactory.getLogger(ToolSandbox.class);

  private final SandboxConfiguration config;
  private final SwarmOrchestrator orchestrator;
  private final List<AtomicCapability> capabilities;
  private final ConcurrentHashMap<String, AtomicCapability> capabilityMap =
      new ConcurrentHashMap<>();

  public ToolSandbox(
      SandboxConfiguration config,
      SwarmOrchestrator orchestrator,
      List<AtomicCapability> capabilities) {
    this.config = config;
    this.orchestrator = orchestrator;
    this.capabilities = capabilities;
  }

  @PostConstruct
  public void init() throws IOException {
    // Initialize sandbox temp directory
    Path tempDir = Path.of(config.getTempDir());
    Files.createDirectories(tempDir);
    System.setProperty("veto.sandbox.tempDir", tempDir.toAbsolutePath().toString());

    // Register capabilities
    for (AtomicCapability cap : capabilities) {
      String name = cap.getName();
      if (config.getAllowedCapabilities().contains(name)) {
        capabilityMap.put(name, cap);
        log.info("C6 Sandbox: Registered capability '{}'", name);
      } else {
        log.warn("C6 Sandbox: Capability '{}' not in allowed list  - ignoring", name);
      }
    }

    if (config.isForbidGenericShell()) {
      log.info("C6 Sandbox: Generic shell execution is FORBIDDEN (veto enforced)");
    }

    log.info(
        "C6 Sandbox: Initialized with {} of {} capabilities registered",
        capabilityMap.size(),
        capabilities.size());
  }

  @PreDestroy
  public void shutdown() {
    log.info("C6 Sandbox: Shut down");
  }

  /**
   * Execute a tool within the sandbox via the orchestrator. Routes to the appropriate atomic
   * capability.
   */
  public CompletableFuture<String> execute(ToolExecutionRequest request) {
    String capName = request.getCapabilityName();

    // Strict check: forbidden generic shell
    if (config.isForbidGenericShell() && isForbiddenExecution(request)) {
      request.markVetoed("Generic shell execution is forbidden by policy");
      return CompletableFuture.completedFuture(
          "{\"status\":\"vetoed\",\"reason\":\"Generic shell execution (run_bash) is forbidden by C6 policy\"}");
    }

    AtomicCapability capability = capabilityMap.get(capName);
    if (capability == null) {
      request.markFailed("Unknown capability: " + capName);
      return CompletableFuture.failedFuture(
          new IllegalArgumentException(
              "Unknown capability: " + capName + ". Allowed: " + capabilityMap.keySet()));
    }

    log.info("C6 Sandbox: Executing '{}' (id={})", capName, request.getId());
    return orchestrator.execute(
        request,
        cap -> {
          log.debug("C6 Sandbox: Worker executing '{}'", cap.getCapabilityName());
          return capability.execute(cap);
        });
  }

  /** Check if a request is attempting forbidden generic execution. */
  private boolean isForbiddenExecution(ToolExecutionRequest request) {
    String name = request.getCapabilityName().toLowerCase();
    return name.contains("run_bash")
        || name.contains("exec_raw")
        || name.contains("shell")
        || name.contains("command_injection")
        || name.contains("eval")
        || name.contains("system");
  }

  /** List all registered capabilities. */
  public Set<String> getRegisteredCapabilities() {
    return Collections.unmodifiableSet(capabilityMap.keySet());
  }

  /** Get a specific capability. */
  public Optional<AtomicCapability> getCapability(String name) {
    return Optional.ofNullable(capabilityMap.get(name));
  }
}
