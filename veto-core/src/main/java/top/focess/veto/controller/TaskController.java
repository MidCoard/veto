package top.focess.veto.controller;

import java.time.Instant;
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
import top.focess.veto.contract.DAGPayload;
import top.focess.veto.controller.dto.*;
import top.focess.veto.i18n.Msg;

/** User-owned, in-memory DAG task registry. Creating a record does not execute or submit work. */
@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.controller.TaskController");

    private final @NonNull RequestAuthorization authorization;

    private record OwnedTask(@NonNull UUID userId, @NonNull DAGPayload payload) {}

    private final @NonNull ConcurrentHashMap<String, OwnedTask> taskStore =
            new ConcurrentHashMap<>();

    /** Creates the local registry with its request authorizer. */
    public TaskController(@NonNull RequestAuthorization authorization) {
        this.authorization = authorization;
    }

    /** POST /api/tasks - Register a local DAG task. */
    @PostMapping(
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public @NonNull ResponseEntity<RestResponse> createTask(
            @RequestBody @NonNull CreateTaskRequest request) {
        UUID userId = authorization.requireUserId();
        String taskType = request.taskType();
        if (taskType == null || taskType.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(new StatusMessageResponse("error", Msg.get("error.task.typeRequired")));
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

        if (taskStore.putIfAbsent(payload.id(), new OwnedTask(userId, payload)) != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Task id already exists");
        }
        log.info("REST: Created task id={}, type={}", payload.id(), payload.taskType());

        return ResponseEntity.ok(
                new TaskCreatedResponse(
                        "ok",
                        payload.id(),
                        payload.taskType(),
                        payload.status().name(),
                        Instant.now().toString()));
    }

    /** GET /api/tasks/{id} - Get DAG task status and details. */
    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    // DAG parameters are intentionally preserved as JSON; Jackson supplies JSON encoding.
    @SuppressWarnings("JvmTaintAnalysis")
    public @NonNull ResponseEntity<RestResponse> getTask(@PathVariable @NonNull String id) {
        DAGPayload payload = ownedPayload(id, authorization.requireUserId());
        if (payload == null) {
            return ResponseEntity.status(404)
                    .body(new StatusMessageResponse("error", Msg.get("error.task.notFound", id)));
        }

        return ResponseEntity.ok(
                new TaskDetailResponse(
                        "ok",
                        payload.id(),
                        payload.taskType(),
                        payload.status().name(),
                        payload.parameters(),
                        payload.dependencies(),
                        payload.sourceComponent(),
                        payload.targetComponent(),
                        payload.createdAt().toString(),
                        payload.updatedAt().toString(),
                        Instant.now().toString()));
    }

    /** GET /api/tasks - List all tasks. */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public @NonNull ResponseEntity<RestResponse> listTasks() {
        UUID userId = authorization.requireUserId();
        List<DAGPayload> owned =
                taskStore.values().stream()
                        .filter(task -> userId.equals(task.userId()))
                        .map(OwnedTask::payload)
                        .toList();
        return ResponseEntity.ok(
                new TaskListResponse(
                        "ok",
                        owned.size(),
                        owned.stream()
                                .map(
                                        p ->
                                                new TaskSummaryResponse(
                                                        p.id(),
                                                        p.taskType(),
                                                        p.status().name(),
                                                        p.createdAt().toString()))
                                .toList(),
                        Instant.now().toString()));
    }

    /** DELETE /api/tasks/{id} - Cancel a task. */
    @DeleteMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    // The validated task identifier is serialized as JSON, never rendered as HTML.
    @SuppressWarnings("JvmTaintAnalysis")
    public @NonNull ResponseEntity<RestResponse> cancelTask(@PathVariable @NonNull String id) {
        UUID userId = authorization.requireUserId();
        DAGPayload existing = ownedPayload(id, userId);
        if (existing == null) {
            return ResponseEntity.status(404)
                    .body(new StatusMessageResponse("error", Msg.get("error.task.notFound", id)));
        }

        DAGPayload cancelled = existing.withStatus(DAGPayload.DAGPayloadStatus.CANCELLED);
        taskStore.put(id, new OwnedTask(userId, cancelled));

        return ResponseEntity.ok(
                new TaskCancelledResponse(
                        "ok",
                        id,
                        DAGPayload.DAGPayloadStatus.CANCELLED.name(),
                        Instant.now().toString()));
    }

    private DAGPayload ownedPayload(@NonNull String id, @NonNull UUID userId) {
        OwnedTask task = taskStore.get(id);
        return task != null && userId.equals(task.userId()) ? task.payload() : null;
    }
}
