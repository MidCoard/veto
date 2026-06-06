package top.focess.veto.controller;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import top.focess.veto.model.ToolExecutionRequest;
import top.focess.veto.sandbox.ToolSandbox;

/**
 * REST controller for C6 Tool Execution via the Sandbox. Submits atomic capability execution
 * requests and retrieves results.
 */
@RestController
@RequestMapping("/api/tool")
public class ToolController {

    private static final Logger log = LoggerFactory.getLogger(ToolController.class);

    private final ToolSandbox toolSandbox;

    private final ConcurrentHashMap<String, CompletableFuture<String>> pendingExecutions =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> completedResults = new ConcurrentHashMap<>();

    public ToolController(ToolSandbox toolSandbox) {
        this.toolSandbox = toolSandbox;
    }

    /** POST /api/tool/execute - Submit a tool execution request. */
    @PostMapping(
            value = "/execute",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> executeTool(
            @RequestBody Map<String, Object> request) {
        String capabilityName = (String) request.get("capabilityName");
        if (capabilityName == null || capabilityName.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(
                            Map.of(
                                    "status", "error",
                                    "message", "capabilityName is required"));
        }

        Map<String, Object> arguments =
                (Map<String, Object>) request.getOrDefault("arguments", Map.of());
        Set<String> requiredCredentials =
                request.containsKey("requiredCredentials")
                        ? Set.copyOf(
                                (java.util.List<String>)
                                        request.getOrDefault(
                                                "requiredCredentials", java.util.List.of()))
                        : Set.of();

        String sessionId = (String) request.getOrDefault("sessionId", "");
        String workflowId = (String) request.getOrDefault("workflowId", "");

        String id = UUID.randomUUID().toString();
        ToolExecutionRequest execRequest =
                new ToolExecutionRequest(
                        id, capabilityName, arguments, requiredCredentials, sessionId, workflowId);

        log.info("REST: /api/tool/execute - capability={}, id={}", capabilityName, id);

        CompletableFuture<String> future = toolSandbox.execute(execRequest);
        pendingExecutions.put(id, future);

        future.whenComplete(
                (result, error) -> {
                    pendingExecutions.remove(id);
                    if (error != null) {
                        completedResults.put(
                                id,
                                Map.of(
                                        "status",
                                        "error",
                                        "id",
                                        id,
                                        "capabilityName",
                                        capabilityName,
                                        "error",
                                        error.getMessage(),
                                        "timestamp",
                                        Instant.now().toString()));
                    } else {
                        completedResults.put(
                                id,
                                Map.of(
                                        "status", "completed",
                                        "id", id,
                                        "capabilityName", capabilityName,
                                        "result", result,
                                        "timestamp", Instant.now().toString()));
                    }
                    java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
                            .schedule(() -> completedResults.remove(id), 5, TimeUnit.MINUTES);
                });

        return ResponseEntity.ok(
                Map.of(
                        "status",
                        "submitted",
                        "id",
                        id,
                        "capabilityName",
                        capabilityName,
                        "requestStatus",
                        execRequest.getStatus().name(),
                        "availableCapabilities",
                        toolSandbox.getRegisteredCapabilities(),
                        "timestamp",
                        Instant.now().toString()));
    }

    /** GET /api/tool/capabilities - List all registered atomic capabilities. */
    @GetMapping(value = "/capabilities", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> listCapabilities() {
        return ResponseEntity.ok(
                Map.of(
                        "status", "ok",
                        "capabilities", toolSandbox.getRegisteredCapabilities(),
                        "timestamp", Instant.now().toString()));
    }
}
