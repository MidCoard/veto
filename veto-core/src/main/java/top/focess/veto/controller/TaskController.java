package top.focess.veto.controller;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import top.focess.veto.bus.RoutingBusService;
import top.focess.veto.controller.dto.CreateTaskRequest;
import top.focess.veto.i18n.Msg;
import top.focess.veto.model.DAGPayload;

/**
 * REST controller for DAG task lifecycle management. Provides endpoints to create, query, and
 * manage DAG payload tasks.
 */
@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.controller.TaskController");

    private final @NonNull RoutingBusService routingBusService;
    private final @NonNull RequestAuthorization authorization;

    private record OwnedTask(@NonNull String owner, @NonNull DAGPayload payload) {}

    private final @NonNull ConcurrentHashMap<String, OwnedTask> taskStore =
            new ConcurrentHashMap<>();

    public TaskController(
            @NonNull RoutingBusService routingBusService,
            @NonNull RequestAuthorization authorization) {
        this.routingBusService = routingBusService;
        this.authorization = authorization;
    }

    /** POST /api/tasks - Create and submit a new DAG task. */
    @PostMapping(
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public @NonNull ResponseEntity<Map<String, Object>> createTask(
            @RequestBody @NonNull CreateTaskRequest request) {
        String owner = authorization.requireUser();
        String taskType = request.taskType();
        if (taskType == null || taskType.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("status", "error", "message", Msg.get("error.task.typeRequired")));
        }

        Map<String, Object> parameters = request.parameters();
        if (parameters == null) parameters = Map.of();
        String sourceComponent = request.sourceComponent();
        if (sourceComponent == null) sourceComponent = "REST-API";
        String targetComponent = request.targetComponent();
        if (targetComponent == null) targetComponent = "bus";
        String requestedId = request.id();
        String taskId = requestedId == null ? UUID.randomUUID().toString() : requestedId;

        DAGPayload payload =
                DAGPayload.builder()
                        .id(taskId)
                        .taskType(taskType)
                        .parameters(parameters)
                        .sourceComponent(sourceComponent)
                        .targetComponent(targetComponent)
                        .build();

        if (taskStore.putIfAbsent(payload.getId(), new OwnedTask(owner, payload)) != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Task id already exists");
        }
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
    // DAG parameters are intentionally preserved as JSON; Jackson supplies JSON encoding.
    @SuppressWarnings("JvmTaintAnalysis")
    public @NonNull ResponseEntity<Map<String, @NonNull Object>> getTask(
            @PathVariable @NonNull String id) {
        DAGPayload payload = ownedPayload(id, authorization.requireUser());
        if (payload == null) {
            return ResponseEntity.status(404)
                    .body(Map.of("status", "error", "message", Msg.get("error.task.notFound", id)));
        }

        HashMap<String, Object> result = new HashMap<>();
        result.put("status", "ok");
        result.put("id", payload.getId());
        result.put("taskType", payload.getTaskType());
        result.put("dagStatus", payload.getStatus().name());
        result.put("parameters", payload.getParameters());
        result.put("dependencies", payload.getDependencies());
        String sourceComponent = payload.getSourceComponent();
        if (sourceComponent != null) result.put("sourceComponent", sourceComponent);
        String targetComponent = payload.getTargetComponent();
        if (targetComponent != null) result.put("targetComponent", targetComponent);
        result.put("createdAt", payload.getCreatedAt().toString());
        result.put("updatedAt", payload.getUpdatedAt().toString());
        result.put("timestamp", Instant.now().toString());
        return ResponseEntity.ok(result);
    }

    /** GET /api/tasks - List all tasks. */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public @NonNull ResponseEntity<Map<String, Object>> listTasks() {
        String owner = authorization.requireUser();
        List<DAGPayload> owned =
                taskStore.values().stream()
                        .filter(task -> owner.equals(task.owner()))
                        .map(OwnedTask::payload)
                        .toList();
        return ResponseEntity.ok(
                Map.of(
                        "status",
                        "ok",
                        "total",
                        owned.size(),
                        "tasks",
                        owned.stream()
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
    // The validated task identifier is serialized as JSON, never rendered as HTML.
    @SuppressWarnings("JvmTaintAnalysis")
    public @NonNull ResponseEntity<Map<String, @NonNull Object>> cancelTask(
            @PathVariable @NonNull String id) {
        String owner = authorization.requireUser();
        DAGPayload existing = ownedPayload(id, owner);
        if (existing == null) {
            return ResponseEntity.status(404)
                    .body(Map.of("status", "error", "message", Msg.get("error.task.notFound", id)));
        }

        DAGPayload cancelled = existing.withStatus(DAGPayload.DAGPayloadStatus.CANCELLED);
        taskStore.put(id, new OwnedTask(owner, cancelled));

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

    private DAGPayload ownedPayload(@NonNull String id, @NonNull String owner) {
        OwnedTask task = taskStore.get(id);
        return task != null && owner.equals(task.owner()) ? task.payload() : null;
    }
}
