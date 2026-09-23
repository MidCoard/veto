package top.focess.veto.controller;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.focess.veto.api.process.TaskInfo;
import top.focess.veto.controller.dto.*;
import top.focess.veto.i18n.Msg;
import top.focess.veto.sandbox.BackgroundTaskManager;
import top.focess.veto.session.SessionService;
import top.focess.veto.vault.KeysteadVault;

/**
 * REST surface for a session's {@code run_task} background tasks — the {@link
 * BackgroundTaskManager} registry, distinct from the legacy DAG {@code /api/tasks}. Lists the
 * session's tasks (running first, then the stopped ones so their exit codes stay visible) and lets
 * the user stop a running one. This is the web counterpart of the agent's {@code view_task} /
 * {@code stop_task} tools.
 */
@RestController
@RequestMapping("/api/sessions")
public class SessionTasksController {

    private final @NonNull SessionService sessionService;
    private final @NonNull BackgroundTaskManager taskManager;
    private final @NonNull KeysteadVault vault;

    public SessionTasksController(
            @NonNull SessionService sessionService,
            @NonNull BackgroundTaskManager taskManager,
            @NonNull KeysteadVault vault) {
        this.sessionService = sessionService;
        this.taskManager = taskManager;
        this.vault = vault;
    }

    /** Tail of merged output carried per task in the list response. */
    private static final int LIST_OUTPUT_LINES = 20;

    /** GET /api/sessions/{name}/tasks — the session's background tasks, running first. */
    @GetMapping("/{name}/tasks")
    public @NonNull ResponseEntity<RestResponse> list(@PathVariable @NonNull String name) {
        String agentId = RequestAuthorization.requireAgentId(name, sessionService, vault);
        List<TaskInfo> tasks = taskManager.list(agentId);
        List<BackgroundTaskResponse> rows = new ArrayList<>(tasks.size());
        for (TaskInfo task : tasks) {
            rows.add(
                    toResponse(
                            task,
                            taskManager
                                    .output(agentId, task.taskId(), LIST_OUTPUT_LINES)
                                    .orElse("")));
        }
        // Running tasks first; otherwise keep registry (insertion) order.
        rows.sort(java.util.Comparator.comparing(BackgroundTaskResponse::alive).reversed());
        return ResponseEntity.ok(new BackgroundTaskListResponse("ok", rows));
    }

    /**
     * DELETE /api/sessions/{name}/tasks/{taskId} — force-stops a RUNNING task, removes a STOPPED
     * one from the registry (the response's {@code status} tells which happened). The UI's Stop and
     * Remove buttons both land here; the task's alive flag picks the action.
     */
    @DeleteMapping(value = "/{name}/tasks/{taskId}", produces = MediaType.APPLICATION_JSON_VALUE)
    // Task metadata is intentionally returned as JSON; it is not inserted into an HTML context.
    @SuppressWarnings("JvmTaintAnalysis")
    public @NonNull ResponseEntity<RestResponse> stopOrRemove(
            @PathVariable @NonNull String name, @PathVariable @NonNull String taskId) {
        String agentId = RequestAuthorization.requireAgentId(name, sessionService, vault);
        boolean alive = taskManager.status(agentId, taskId).map(TaskInfo::alive).orElse(false);
        // Try the state-matching action; if the task flipped state meanwhile (exit landed between
        // the check and the action), the other action applies.
        Optional<TaskAction> result =
                alive
                        ? taskManager
                                .stop(agentId, taskId, BackgroundTaskManager.ExitCause.USER_STOP)
                                .map(info -> new TaskAction("stopped", info))
                        : taskManager
                                .remove(agentId, taskId)
                                .map(info -> new TaskAction("removed", info));
        if (result.isEmpty()) {
            result =
                    alive
                            ? taskManager
                                    .remove(agentId, taskId)
                                    .map(info -> new TaskAction("removed", info))
                            : taskManager
                                    .stop(
                                            agentId,
                                            taskId,
                                            BackgroundTaskManager.ExitCause.USER_STOP)
                                    .map(info -> new TaskAction("stopped", info));
        }
        return result.map(
                        entry ->
                                ResponseEntity.<RestResponse>ok(
                                        new BackgroundTaskActionResponse(
                                                entry.status(), toResponse(entry.task(), null))))
                .orElseGet(
                        () ->
                                ResponseEntity.status(HttpStatus.NOT_FOUND)
                                        .body(
                                                new StatusMessageResponse(
                                                        "error",
                                                        Msg.get("error.task.notFound", taskId))));
    }

    private record TaskAction(@NonNull String status, @NonNull TaskInfo task) {}

    private @NonNull BackgroundTaskResponse toResponse(
            @NonNull TaskInfo task, String recentOutput) {
        Instant finishedAt = task.finishedAt();
        long uptimeSeconds =
                finishedAt != null
                        ? Duration.between(task.startedAt(), finishedAt).toSeconds()
                        : task.uptimeSeconds();
        return new BackgroundTaskResponse(
                task.taskId(),
                task.command(),
                task.cwd(),
                task.pid(),
                task.alive(),
                task.exitCode(),
                task.startedAt().toString(),
                finishedAt == null ? null : finishedAt.toString(),
                Math.max(0, uptimeSeconds),
                recentOutput);
    }
}
