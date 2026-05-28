package top.focess.veto.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * C4 MCP Server Registry â€?dynamically discovers and mounts local/enterprise MCP Servers.
 * Watches the configured registry path for new server definitions.
 */
@Component
public class MCPServerRegistry {

    private static final Logger log = LoggerFactory.getLogger(MCPServerRegistry.class);

    private final MCPConfiguration config;
    private final ObjectMapper objectMapper;

    private final ConcurrentHashMap<String, MCPServerDefinition> servers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<MCProtocol>> toolRegistry = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<RegistryListener> listeners = new CopyOnWriteArrayList<>();
    private WatchService watchService;

    public MCPServerRegistry(MCPConfiguration config, ObjectMapper objectMapper) {
        this.config = config;
        this.objectMapper = objectMapper;
    }

    /**
     * Scan the registry path for MCP server definitions.
     */
    public void discoverServers() {
        Path registryPath = Paths.get(config.getServerRegistryPath());
        if (!Files.exists(registryPath)) {
            try {
                Files.createDirectories(registryPath);
                log.info("C4 MCP: Created registry directory at {}", registryPath);
            } catch (IOException e) {
                log.warn("C4 MCP: Cannot create registry directory", e);
                return;
            }
        }

        log.info("C4 MCP: Discovering servers in {}", registryPath);
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(registryPath, "*.json")) {
            for (Path entry : stream) {
                registerServer(entry);
            }
        } catch (IOException e) {
            log.error("C4 MCP: Discovery scan failed", e);
        }
        log.info("C4 MCP: Discovery complete. {} servers registered.", servers.size());
    }

    /**
     * Register a server from a JSON definition file.
     */
    public MCPServerDefinition registerServer(Path definitionFile) {
        try {
            byte[] content = Files.readAllBytes(definitionFile);
            MCPServerDefinition definition = objectMapper.readValue(
                content, MCPServerDefinition.class);

            servers.put(definition.getId(), definition);
            log.info("C4 MCP: Registered server '{}' (type={}, tools={})",
                definition.getId(), definition.getServerType(), definition.getTools().size());

            // Register tool protocols
            List<MCProtocol> protocols = new ArrayList<>();
            for (var toolEntry : definition.getTools().entrySet()) {
                MCProtocol protocol = new MCProtocol(
                    definition.getId(),
                    toolEntry.getKey(),
                    (String) toolEntry.getValue().getOrDefault("description", ""),
                    (Map<String, Object>) toolEntry.getValue().getOrDefault("inputSchema", Map.of()),
                    Map.of("type", definition.getServerType())
                );
                protocols.add(protocol);
            }
            toolRegistry.put(definition.getId(), protocols);

            // Notify listeners
            for (RegistryListener listener : listeners) {
                listener.onServerRegistered(definition);
            }

            return definition;
        } catch (IOException e) {
            log.error("C4 MCP: Failed to register server from {}", definitionFile, e);
            return null;
        }
    }

    /**
     * Get all protocols across all registered servers.
     */
    public List<MCProtocol> getAllProtocols() {
        List<MCProtocol> all = new ArrayList<>();
        toolRegistry.values().forEach(all::addAll);
        return Collections.unmodifiableList(all);
    }

    /**
     * Get protocols for a specific server.
     */
    public List<MCProtocol> getServerProtocols(String serverId) {
        return toolRegistry.getOrDefault(serverId, Collections.emptyList());
    }

    /**
     * Get all registered server definitions.
     */
    public Map<String, MCPServerDefinition> getServers() {
        return Collections.unmodifiableMap(servers);
    }

    /**
     * Get a specific server definition.
     */
    public Optional<MCPServerDefinition> getServer(String serverId) {
        return Optional.ofNullable(servers.get(serverId));
    }

    /**
     * Remove a server.
     */
    public void unregisterServer(String serverId) {
        MCPServerDefinition removed = servers.remove(serverId);
        toolRegistry.remove(serverId);
        if (removed != null) {
            log.info("C4 MCP: Unregistered server '{}'", serverId);
            for (RegistryListener listener : listeners) {
                listener.onServerUnregistered(serverId);
            }
        }
    }

    public void addListener(RegistryListener listener) {
        listeners.add(listener);
    }

    public interface RegistryListener {
        default void onServerRegistered(MCPServerDefinition definition) {}
        default void onServerUnregistered(String serverId) {}
    }

    /**
     * JSON-deserializable MCP server definition.
     */
    public static class MCPServerDefinition {
        private String id;
        private String name;
        private String version;
        private String serverType;  // "local", "enterprise", "wasm"
        private String endpoint;
        private String wasmModulePath;
        private Map<String, Map<String, Object>> tools;
        private Map<String, String> metadata = new HashMap<>();

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getVersion() { return version; }
        public void setVersion(String version) { this.version = version; }
        public String getServerType() { return serverType; }
        public void setServerType(String serverType) { this.serverType = serverType; }
        public String getEndpoint() { return endpoint; }
        public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
        public String getWasmModulePath() { return wasmModulePath; }
        public void setWasmModulePath(String wasmModulePath) { this.wasmModulePath = wasmModulePath; }
        public Map<String, Map<String, Object>> getTools() { return tools; }
        public void setTools(Map<String, Map<String, Object>> tools) { this.tools = tools; }
        public Map<String, String> getMetadata() { return metadata; }
        public void setMetadata(Map<String, String> metadata) { this.metadata = metadata; }

        @Override
        public String toString() {
            return "MCPServerDefinition{id='" + id + "', name='" + name + "', type=" + serverType + "}";
        }
    }
}
