package top.focess.veto.mcp;

import top.focess.veto.model.ToolExecutionRequest;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * C4 MCP Extensibility Engine  - top-level service.
 * Implements the Model Context Protocol for dynamic discovery and mounting of
 * local/enterprise MCP Servers. Third-party plugins run within WASM sandboxes.
 */
@Service
public class MCPEngine {

    private static final Logger log = LoggerFactory.getLogger(MCPEngine.class);

    private final MCPServerRegistry registry;
    private final MCPConfiguration config;
    private final WASMSandbox wasmSandbox;

    private final ConcurrentHashMap<String, MCProtocol> activeProtocols = new ConcurrentHashMap<>();
    private ScheduledExecutorService discoveryScheduler;

    public MCPEngine(MCPServerRegistry registry, MCPConfiguration config, WASMSandbox wasmSandbox) {
        this.registry = registry;
        this.config = config;
        this.wasmSandbox = wasmSandbox;
    }

    @PostConstruct
    public void init() {
        // Initial discovery
        registry.discoverServers();
        syncActiveProtocols();

        // Periodic discovery
        discoveryScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "veto-mcp-discovery");
            t.setDaemon(true);
            return t;
        });
        discoveryScheduler.scheduleAtFixedRate(
            this::discoveryCycle,
            config.getDiscoveryIntervalMs(),
            config.getDiscoveryIntervalMs(),
            TimeUnit.MILLISECONDS
        );

        log.info("C4 MCP Engine: Initialized. {} active protocols.", activeProtocols.size());
    }

    /**
     * Execute a tool through an MCP server.
     */
    public CompletableFuture<String> executeTool(ToolExecutionRequest request) {
        String capabilityName = request.getCapabilityName();

        // Find matching protocol
        MCProtocol protocol = findProtocol(capabilityName);
        if (protocol == null) {
            return CompletableFuture.failedFuture(
                new IllegalArgumentException("No MCP tool found for: " + capabilityName));
        }

        var serverDef = registry.getServer(protocol.getServerId());
        if (serverDef.isEmpty()) {
            return CompletableFuture.failedFuture(
                new IllegalStateException("Server not registered: " + protocol.getServerId()));
        }

        // Route to appropriate executor
        var server = serverDef.get();
        if ("wasm".equals(server.getServerType())) {
            return executeInWASM(server, request);
        } else {
            return executeViaEndpoint(server, request);
        }
    }

    private CompletableFuture<String> executeViaEndpoint(MCPServerRegistry.MCPServerDefinition server,
                                                         ToolExecutionRequest request) {
        // For local/enterprise servers, route via endpoint
        log.debug("C4 MCP: Executing '{}' via endpoint {}", request.getCapabilityName(), server.getEndpoint());
        return CompletableFuture.completedFuture(
            "{\"status\":\"routed\",\"server\":\"" + server.getId() +
            "\",\"tool\":\"" + request.getCapabilityName() + "\"}");
    }

    private CompletableFuture<String> executeInWASM(MCPServerRegistry.MCPServerDefinition server,
                                                    ToolExecutionRequest request) {
        if (!config.isWasmSandboxEnabled()) {
            return CompletableFuture.completedFuture(
                "{\"error\":\"WASM sandbox is disabled\"}");
        }
        return wasmSandbox.execute(server, request);
    }

    /**
     * Find a protocol matching the given capability name.
     */
    public MCProtocol findProtocol(String capabilityName) {
        return activeProtocols.get(capabilityName);
    }

    /**
     * Sync active protocols from the registry.
     */
    private void syncActiveProtocols() {
        activeProtocols.clear();
        for (MCProtocol protocol : registry.getAllProtocols()) {
            if (protocol.getStatus() == MCProtocol.MCProtocolStatus.ACTIVE) {
                activeProtocols.put(protocol.getToolName(), protocol);
            }
        }
    }

    private void discoveryCycle() {
        try {
            registry.discoverServers();
            syncActiveProtocols();
            log.debug("C4 MCP: Discovery cycle complete. {} protocols active.", activeProtocols.size());
        } catch (Exception e) {
            log.warn("C4 MCP: Discovery cycle error", e);
        }
    }

    public List<MCProtocol> getActiveProtocols() {
        return List.copyOf(activeProtocols.values());
    }

    public void shutdown() {
        if (discoveryScheduler != null) {
            discoveryScheduler.shutdown();
        }
        log.info("C4 MCP Engine: Shut down");
    }
}
