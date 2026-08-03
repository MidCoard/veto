package top.focess.veto.controller;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import top.focess.veto.bus.RoutingBusService;
import top.focess.veto.model.DAGPayload;

/**
 * REST controller for DAG task lifecycle management. Provides endpoints to create, query, and
 * manage DAG payload tasks.
 */
@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private static final Logger log = LoggerFactory.getLogger(TaskController.class);

    private final @NonNull RoutingBusService routingBusService;
    private final @NonNull ConcurrentHashMap<String, DAGPayload> taskStore =
            new ConcurrentHashMap<>();

    public
    @NonNull
    TaskController(@NonNull RoutingBusService routingBusService) {
        this.routingBusService = routingBusService;
    }

    /** POST /api/tasks - Create and submit a new DAG task. */
    @PostMapping(
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public @NonNull ResponseEntity<Map<String, Object>> createTask(
            @NonNull @RequestBody Map<String, Object> request) {
        String taskType = (String) request.get("taskType");
        if (taskType == null || taskType.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(
                            Map.of(
                                    "status", "error",
                                    "message", "taskType is required"));
        }

        Map<String, Object> parameters =
                (Map<String, Object>) request.getOrDefault("parameters", Map.of());
        String sourceComponent = (String) request.getOrDefault("sourceComponent", "REST-API");
        String targetComponent = (String) request.getOrDefault("targetComponent", "bus");

        DAGPayload payload =
                DAGPayload.builder()
                        .id(
                                request.containsKey("id")
                                        ? (String) request.get("id")
                                        : UUID.randomUUID().toString())
                        .taskType(taskType)
                        .parameters(parameters)
                        .sourceComponent(sourceComponent)
                        .targetComponent(targetComponent)
                        .build();

        taskStore.put(payload.getId(), payload);
        log.info("REST: Created task id={}, type={}", payload.getId(), payload.getTaskType());

        if (routingBusService.isConnected()) {
            routingBusService.submitDAGPayload(payload);
        } else {
            log.warn("REST: Bus not connected - task stored locally only");
        }

        return ResponseEntity.ok(
                Map.of(
                        "status", "ok",
                        "id", payload.getId(),
                        "taskType", payload.getTaskType(),
                        "dagStatus", payload.getStatus().name(),
                        "timestamp", Instant.now().toString()));
    }

    /** GET /api/tasks/{id} - Get DAG task status and details. */
    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public @NonNull ResponseEntity<Map<String, @NonNull Object>> getTask(
            @NonNull @PathVariable("id") String id) {
        DAGPayload payload = taskStore.get(id);
        if (payload == null) {
            return ResponseEntity.notFound().build();
        }

        java.util.HashMap<String, Object> result = new java.util.HashMap<>();
        result.put("status", "ok");
        result.put("id", payload.getId());
        result.put("taskType", payload.getTaskType());
        result.put("dagStatus", payload.getStatus().name());
        result.put("parameters", payload.getParameters());
        result.put("dependencies", payload.getDependencies());
        result.put("sourceComponent", payload.getSourceComponent());
        result.put("targetComponent", payload.getTargetComponent());
        result.put("createdAt", payload.getCreatedAt().toString());
        result.put("updatedAt", payload.getUpdatedAt().toString());
        result.put("timestamp", Instant.now().toString());
        return ResponseEntity.ok(result);
    }

    /** GET /api/tasks - List all tasks. */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public @NonNull ResponseEntity<Map<String, Object>> listTasks() {
        return ResponseEntity.ok(
                Map.of(
                        "status",
                        "ok",
                        "total",
                        taskStore.size(),
                        "tasks",
                        taskStore.values().stream()
                                .map(
                                        p ->
                                                Map.of(
                                                        "id",
                                                        p.getId(),
                                                        "taskType",
                                                        p.getTaskType(),
                                                        "status",
                                                        p.getStatus().name(),
                                                        "createdAt",
                                                        p.getCreatedAt().toString()))
                                .toList(),
                        "busConnected",
                        routingBusService.isConnected(),
                        "timestamp",
                        Instant.now().toString()));
    }

    /** DELETE /api/tasks/{id} - Cancel a task. */
    @DeleteMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public @NonNull ResponseEntity<Map<String, @NonNull Object>> cancelTask(
            @NonNull @PathVariable("id") String id) {
        DAGPayload existing = taskStore.get(id);
        if (existing == null) {
            return ResponseEntity.notFound().build();
        }

        DAGPayload cancelled = existing.withStatus(DAGPayload.DAGPayloadStatus.CANCELLED);
        taskStore.put(id, cancelled);

        return ResponseEntity.ok(
                Map.of(
                        "status",
                        "ok",
                        "id",
                        id,
                        "newStatus",
                        DAGPayload.DAGPayloadStatus.CANCELLED.name(),
                        "timestamp",
                        Instant.now().toString()));
    }
}
