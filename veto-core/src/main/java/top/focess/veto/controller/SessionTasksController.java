package top.focess.veto.controller;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
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

    public
    @NonNull
    SessionTasksController(
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
    public @NonNull ResponseEntity<Map<String, @NonNull Object>> list(
            @NonNull @PathVariable("name") String name) {
        String agentId = requireAgentId(name);
        List<BackgroundTaskManager.TaskInfo> tasks = taskManager.list(agentId);
        List<Map<String, Object>> rows = new ArrayList<>(tasks.size());
        for (BackgroundTaskManager.TaskInfo task : tasks) {
            Map<String, Object> row = toJson(task);
            row.put(
                    "recentOutput",
                    taskManager.output(agentId, task.taskId(), LIST_OUTPUT_LINES).orElse(""));
            rows.add(row);
        }
        // Running tasks first; otherwise keep registry (insertion) order.
        rows.sort(
                (a, b) ->
                        Boolean.compare(
                                Boolean.TRUE.equals(b.get("alive")),
                                Boolean.TRUE.equals(a.get("alive"))));
        return ResponseEntity.ok(Map.of("status", "ok", "tasks", rows));
    }

    /**
     * DELETE /api/sessions/{name}/tasks/{taskId} — force-stops a RUNNING task, removes a STOPPED
     * one from the registry (the response's {@code status} tells which happened). The UI's Stop and
     * Remove buttons both land here; the task's alive flag picks the action.
     */
    @DeleteMapping("/{name}/tasks/{taskId}")
    public @NonNull ResponseEntity<Map<String, @NonNull Object>> stopOrRemove(
            @NonNull @PathVariable("name") String name,
            @NonNull @PathVariable("taskId") String taskId) {
        String agentId = requireAgentId(name);
        boolean alive =
                taskManager
                        .status(agentId, taskId)
                        .map(BackgroundTaskManager.TaskInfo::alive)
                        .orElse(false);
        // Try the state-matching action; if the task flipped state meanwhile (exit landed between
        // the check and the action), the other action applies.
        java.util.Optional<Map.Entry<String, BackgroundTaskManager.TaskInfo>> result =
                alive
                        ? taskManager
                                .stop(agentId, taskId, BackgroundTaskManager.ExitCause.USER_STOP)
                                .map(info -> Map.entry("stopped", info))
                        : taskManager
                                .remove(agentId, taskId)
                                .map(info -> Map.entry("removed", info));
        if (result.isEmpty()) {
            result =
                    alive
                            ? taskManager
                                    .remove(agentId, taskId)
                                    .map(info -> Map.entry("removed", info))
                            : taskManager
                                    .stop(
                                            agentId,
                                            taskId,
                                            BackgroundTaskManager.ExitCause.USER_STOP)
                                    .map(info -> Map.entry("stopped", info));
        }
        return result.<ResponseEntity<Map<String, @NonNull Object>>>map(
                        entry ->
                                ResponseEntity.ok(
                                        Map.of(
                                                "status",
                                                entry.getKey(),
                                                "task",
                                                toJson(entry.getValue()))))
                .orElseGet(
                        () ->
                                ResponseEntity.status(HttpStatus.NOT_FOUND)
                                        .body(
                                                Map.of(
                                                        "status",
                                                        "error",
                                                        "message",
                                                        Msg.get("error.task.notFound", taskId))));
    }

    /** Flattens a {@link BackgroundTaskManager.TaskInfo} into its wire shape. */
    private @NonNull Map<String, Object> toJson(BackgroundTaskManager.@NonNull TaskInfo task) {
        long uptimeSeconds =
                task.finishedAt() != null
                        ? Duration.between(task.startedAt(), task.finishedAt()).toSeconds()
                        : task.uptimeSeconds();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("taskId", task.taskId());
        row.put("command", task.command());
        row.put("cwd", task.cwd());
        row.put("pid", task.pid());
        row.put("alive", task.alive());
        row.put("exitCode", task.exitCode());
        row.put("startedAt", task.startedAt().toString());
        row.put("finishedAt", task.finishedAt() == null ? null : task.finishedAt().toString());
        row.put("uptimeSeconds", Math.max(0, uptimeSeconds));
        return row;
    }

    /** Resolves the session's primary agent id, enforcing authentication + existence. */
    private @NonNull String requireAgentId(@NonNull String name) {
        String user = vault.currentUser();
        if (user == null) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED, Msg.get("error.auth.notAuthenticated"));
        }
        String agentId = sessionService.primaryAgentIdFor(name, user).orElse(null);
        if (agentId == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, Msg.get("error.session.notFound", name));
        }
        return agentId;
    }
}
