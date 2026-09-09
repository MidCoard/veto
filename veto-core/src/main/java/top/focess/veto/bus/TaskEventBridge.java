package top.focess.veto.bus;

import jakarta.annotation.PostConstruct;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import top.focess.veto.model.SessionRepository;
import top.focess.veto.monitor.MonitorService;
import top.focess.veto.sandbox.BackgroundTaskManager;

/**
 * Bridges background-task lifecycle events (sandbox) onto the {@link DeltaBroker} so every
 * subscriber (the web UI, the terminal adapter) sees tasks start and exit without polling the task
 * tools. The sandbox stays free of any bus dependency — it only notifies the {@link
 * BackgroundTaskManager.TaskListener}; this bridge is the single component that knows both sides.
 */
@Component
public class TaskEventBridge implements BackgroundTaskManager.TaskListener {

    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.bus.TaskEventBridge");

    private final @NonNull BackgroundTaskManager taskManager;
    private final @NonNull DeltaBroker broker;
    private final @NonNull SessionRepository sessions;
    private final @NonNull MonitorService monitors;

    public TaskEventBridge(
            @NonNull BackgroundTaskManager taskManager,
            @NonNull DeltaBroker broker,
            @NonNull SessionRepository sessions,
            @NonNull MonitorService monitors) {
        this.taskManager = taskManager;
        this.broker = broker;
        this.sessions = sessions;
        this.monitors = monitors;
    }

    @PostConstruct
    void register() {
        taskManager.setTaskListener(this);
    }

    @Override
    public void onTaskStarted(BackgroundTaskManager.@NonNull TaskInfo info) {
        observe(info, BackgroundTaskManager.ExitCause.NATURAL);
        publish(DeltaFrame.Kind.TASK_STARTED, info);
    }

    @Override
    public void onTaskExited(BackgroundTaskManager.@NonNull TaskInfo info) {
        onTaskExited(info, BackgroundTaskManager.ExitCause.NATURAL);
    }

    @Override
    public void onTaskExited(
            BackgroundTaskManager.@NonNull TaskInfo info,
            BackgroundTaskManager.@NonNull ExitCause cause) {
        observe(info, cause);
        publish(DeltaFrame.Kind.TASK_EXITED, info);
    }

    @Override
    public void onTaskRemoved(BackgroundTaskManager.@NonNull TaskInfo info) {
        UUID sessionId = info.sessionId();
        if (sessionId == null) return;
        broker.publish(
                DeltaFrame.builder()
                        .sessionId(sessionId)
                        .kind(DeltaFrame.Kind.SESSION_INVALIDATED)
                        .attr(
                                "resources",
                                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance
                                        .arrayNode()
                                        .add("tasks"))
                        .build());
    }

    private void observe(
            BackgroundTaskManager.@NonNull TaskInfo info,
            BackgroundTaskManager.@NonNull ExitCause cause) {
        UUID session = info.sessionId();
        if (session == null) return;
        sessions.findById(session.toString())
                .ifPresent(row -> monitors.observeProcess(row.getOwner(), info, cause));
    }

    private void publish(
            DeltaFrame.@NonNull Kind kind, BackgroundTaskManager.@NonNull TaskInfo info) {
        UUID sessionId = info.sessionId();
        if (sessionId == null) {
            // A task without a session (standalone / test) has no session-scoped subscriber to
            // route to; the tool_result of run_task already told the model the taskId.
            return;
        }
        try {
            DeltaFrame.Builder b =
                    DeltaFrame.builder()
                            .sessionId(sessionId)
                            .kind(kind)
                            .attr("taskId", info.taskId())
                            .attr("agentId", info.agentId())
                            .attr("command", info.command())
                            .attr("cwd", info.cwd())
                            .attr("pid", info.pid())
                            .attr("alive", info.alive())
                            .text(info.command());
            Integer exitCode = info.exitCode();
            if (exitCode != null) {
                b.attr("exitCode", exitCode);
            }
            broker.publish(b.build());
        } catch (RuntimeException e) {
            log.warn("TaskEventBridge: publish failed (kind={}, task={})", kind, info.taskId(), e);
        }
    }
}
