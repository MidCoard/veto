package top.focess.veto.bus;

import jakarta.annotation.PostConstruct;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import top.focess.veto.sandbox.BackgroundTaskManager;

/**
 * Bridges background-task lifecycle events (sandbox) onto the {@link DeltaBroker} so every
 * subscriber (the web UI, the terminal adapter) sees tasks start and exit without polling the task
 * tools. The sandbox stays free of any bus dependency — it only notifies the {@link
 * BackgroundTaskManager.TaskListener}; this bridge is the single component that knows both sides.
 */
@Component
public class TaskEventBridge {

    private static final Logger log = LoggerFactory.getLogger(TaskEventBridge.class);

    private final @NonNull BackgroundTaskManager taskManager;
    private final @NonNull DeltaBroker broker;

    public
    @NonNull
    TaskEventBridge(@NonNull BackgroundTaskManager taskManager, @NonNull DeltaBroker broker) {
        this.taskManager = taskManager;
        this.broker = broker;
    }

    @PostConstruct
    void register() {
        taskManager.setTaskListener(
                new BackgroundTaskManager.TaskListener() {
                    @Override
                    public void onTaskStarted(BackgroundTaskManager.@NonNull TaskInfo info) {
                        publish(DeltaFrame.Kind.TASK_STARTED, info);
                    }

                    @Override
                    public void onTaskExited(BackgroundTaskManager.@NonNull TaskInfo info) {
                        publish(DeltaFrame.Kind.TASK_EXITED, info);
                    }
                });
    }

    private void publish(
            DeltaFrame.@NonNull Kind kind, BackgroundTaskManager.@NonNull TaskInfo info) {
        if (info.sessionId() == null) {
            // A task without a session (standalone / test) has no session-scoped subscriber to
            // route to; the tool_result of run_task already told the model the taskId.
            return;
        }
        try {
            DeltaFrame.Builder b =
                    DeltaFrame.builder()
                            .sessionId(info.sessionId())
                            .kind(kind)
                            .attr("taskId", info.taskId())
                            .attr("agentId", info.agentId())
                            .attr("command", info.command())
                            .attr("cwd", info.cwd())
                            .attr("pid", info.pid())
                            .attr("alive", info.alive())
                            .text(info.command());
            if (info.exitCode() != null) {
                b.attr("exitCode", info.exitCode());
            }
            broker.publish(b.build());
        } catch (RuntimeException e) {
            log.warn("TaskEventBridge: publish failed (kind={}, task={})", kind, info.taskId(), e);
        }
    }
}
