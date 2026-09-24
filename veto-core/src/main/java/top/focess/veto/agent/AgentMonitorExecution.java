package top.focess.veto.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.AgentRuntimeState.ActivatedObservation;
import top.focess.veto.agent.AgentRuntimeState.BreakerTripException;
import top.focess.veto.agent.AgentRuntimeState.RequestContinuation;
import top.focess.veto.api.agent.AgentAction;
import top.focess.veto.api.agent.AgentState;
import top.focess.veto.monitor.MonitorRecord;
import top.focess.veto.monitor.MonitorService;
import top.focess.veto.monitor.RequestContinuationStore;
import top.focess.veto.sandbox.BackgroundTaskManager;
import top.focess.veto.vault.KeysteadVault;

/** Maintains monitor wakeups, request continuations and background-task observations. */
final class AgentMonitorExecution {
    private final @NonNull AgentRuntimeState runtime;

    AgentMonitorExecution(@NonNull AgentRuntimeState runtime) {
        this.runtime = runtime;
    }

    void attachMonitorVault(@NonNull KeysteadVault vault) {
        runtime.monitorVault = vault;
    }

    void attachContinuationStore(@NonNull RequestContinuationStore store) {
        runtime.continuationStore = store;
    }

    RequestContinuation findContinuation(@NonNull String requestId) {
        RequestContinuation value = runtime.requestContinuations.get(requestId);
        RequestContinuationStore store = runtime.continuationStore;
        if (value == null && store != null) {
            var saved = store.load(runtime.sessionId, runtime.agentId, requestId).orElse(null);
            if (saved != null) {
                value = new RequestContinuation(saved.task(), saved.consumedCalls());
                runtime.requestContinuations.put(requestId, value);
            }
        }
        return value;
    }

    void reserveRequestCall() {
        runtime.breaker.recordModelCall();
        RequestContinuationStore store = runtime.continuationStore;
        String request = runtime.activeRequestId;
        if (store != null && request != null)
            store.save(
                    runtime.sessionId,
                    runtime.agentId,
                    request,
                    runtime.activeUserTask,
                    runtime.breaker.count());
        rememberRequest();
    }

    void rememberRequest() {
        String requestId = runtime.activeRequestId;
        if (requestId != null)
            runtime.requestContinuations.put(
                    requestId,
                    new RequestContinuation(runtime.activeUserTask, runtime.breaker.count()));
    }

    boolean belongsToActiveRequest(MonitorRecord.@NonNull Event event) {
        return runtime.activeMonitorEventId != null
                ? runtime.activeMonitorEventId.equals(event.id())
                : runtime.activeRequestId != null
                        && runtime.activeRequestId.equals(event.requestId());
    }

    void attachMonitor(@NonNull MonitorService service) {
        runtime.monitorService = service;
    }

    void signalMonitor() {
        if (runtime.sessionAlive && runtime.monitorQueued.compareAndSet(false, true))
            runtime.actionQueue.add(new AgentAction.MonitorAction());
    }

    @NonNull List<MonitorRecord.Event> pendingActiveRequestEvents(@NonNull MonitorService service) {
        List<MonitorRecord.Event> eligible = new ArrayList<>();
        for (MonitorRecord.Event event :
                service.pending(runtime.agentId, runtime.sessionId.toString())) {
            String request = event.requestId();
            boolean cancelled =
                    request != null
                            && runtime.output().history().stream()
                                    .anyMatch(
                                            turn ->
                                                    turn.type() == TurnType.EXECUTION_ERROR
                                                            && "CANCELLED"
                                                                    .equals(
                                                                            turn.payload()
                                                                                    .get("outcome"))
                                                            && request.equals(
                                                                    turn.payload()
                                                                            .get("requestId")));
            if (!cancelled) {
                eligible.add(event);
                continue;
            }
            try {
                service.activationCancelled(runtime.agentId, runtime.sessionId.toString(), event);
            } catch (RuntimeException error) {
                AgentRuntimeState.log.warn(
                        "Cancelled request observation {} awaits persistence", event.id(), error);
            }
        }
        return eligible;
    }

    boolean injectMonitorEvents() {
        MonitorService service = runtime.monitorService;
        if (service == null) return false;
        runtime.lifecycle().checkExecutionBoundary();
        boolean inserted = false;
        for (MonitorRecord.Event event : pendingActiveRequestEvents(service)) {
            if (!belongsToActiveRequest(event)) continue;
            boolean recorded =
                    runtime.output().history().stream()
                            .anyMatch(t -> event.id().equals(t.payload().get("eventId")));
            if (!recorded) {
                String originRequestId = event.requestId();
                String originDispatchId = event.dispatchId();
                runtime.output()
                        .appendTurn(
                                new TurnRecord(
                                        ++runtime.turnNumber,
                                        TurnType.MONITOR_EVENT,
                                        Map.of(
                                                "eventId",
                                                event.id(),
                                                "monitorId",
                                                event.monitorId(),
                                                "kind",
                                                event.kind(),
                                                "content",
                                                "Notification for the following originating task (later"
                                                        + " user requests remain separate):\n"
                                                        + runtime.activeUserTask
                                                        + "\n\nObservation:\n"
                                                        + event.content(),
                                                "requestId",
                                                originRequestId == null ? "" : originRequestId,
                                                "dispatchId",
                                                originDispatchId == null ? "" : originDispatchId),
                                        event.occurredAt()));
            }
            service.acknowledge(runtime.agentId, event);
            service.activationStarted(runtime.agentId, event);
            runtime.activatedMonitorEvents.put(
                    event.id(), new ActivatedObservation(event, runtime.activeRequestId));
            // A retried acknowledgement still needs reasoning, even if the history already exists.
            inserted = true;
        }
        return inserted;
    }

    void completeOrWaitForMonitor() {
        MonitorService service = runtime.monitorService;
        if (service != null
                && (service.hasGroupWork(runtime.agentId, runtime.activeRequestId)
                        || service.hasUndeliveredGroup(runtime.agentId, runtime.activeRequestId))) {
            runtime.waitingForMonitor = true;
            runtime.lifecycle().transitionTo(AgentState.WAITING);
            signalMonitor();
        } else runtime.lifecycle().completeSuccess();
    }

    void processMonitor() {
        KeysteadVault vault = runtime.monitorVault;
        String monitorOwner = runtime.owner;
        if (vault != null && (monitorOwner == null || !vault.isUnlocked(monitorOwner))) return;
        MonitorService service = runtime.monitorService;
        if (service == null
                || runtime.recoveredWait
                || runtime.executionWait != null
                || runtime.awaitingBreakerContinuation
                || runtime.state == AgentState.PAUSED
                || runtime.state == AgentState.INTERCEPTED
                || !runtime.sessionAlive) return;
        synchronized (runtime) {
            // A newly submitted user task owns its own handoff future and goes first.
            if (runtime.actionQueue.stream()
                    .anyMatch(
                            a ->
                                    a instanceof AgentAction.UserPromptAction
                                            || a instanceof AgentAction.DirectUserPromptAction))
                return;
            var events = pendingActiveRequestEvents(service);
            if (events.isEmpty()) return;
            if (runtime.waitingForMonitor) {
                if (events.stream().noneMatch(this::belongsToActiveRequest)) return;
            } else {
                rememberRequest();
                MonitorRecord.Event first = null;
                for (MonitorRecord.Event candidate : events) {
                    String candidateOrigin = candidate.requestId();
                    if (candidateOrigin == null || findContinuation(candidateOrigin) != null) {
                        first = candidate;
                        break;
                    }
                }
                if (first == null) return;
                String origin = first.requestId();
                String requestId = origin == null ? "monitor:" + first.id() : origin;
                RequestContinuation continuation = findContinuation(requestId);
                if (origin != null && continuation == null) {
                    AgentRuntimeState.log.warn(
                            "Monitor event {} awaits unavailable request context {}",
                            first.id(),
                            origin);
                    return;
                }
                runtime.activeRequestId = requestId;
                runtime.activeMonitorEventId = origin == null ? first.id() : null;
                if (continuation != null) {
                    runtime.activeUserTask = continuation.task();
                    runtime.breaker.restoreCount(continuation.consumedCalls());
                } else {
                    runtime.activeUserTask =
                            "Handle this notification without repeating completed work: "
                                    + first.content();
                    if (first.kind().equals("TIME_ONCE")) runtime.breaker.newEpisode();
                }
                runtime.program = null;
                runtime.handlingDirectUserPrompt = false;
            }
            if (runtime.resultFuture.isDone() && !runtime.handlingDirectUserPrompt) {
                runtime.resultFuture = new CompletableFuture<>();
                runtime.callback = null;
            }
            runtime.monitorResultFuture = runtime.resultFuture;
            runtime.monitorCallback = runtime.callback;
            runtime.waitingForMonitor = false;
            runtime.lifecycle().transitionTo(AgentState.RUNNING);
        }
        try {
            runtime.models().refreshSystemHistory();
            boolean inserted = injectMonitorEvents();
            if (!inserted) return;
            runtime.preparedFirstPrompt = null;
            runtime.program = null;
            runtime.completionToolFinished = false;
            runtime.pendingResponse = null;
            runtime.submissionRequest = null;
            runtime.models().runAutonomous();
            completeOrWaitForMonitor();
        } catch (BreakerTripException e) {
            runtime.lifecycle().completeBreaker();
        } catch (Exception e) {
            runtime.lifecycle().completeFailure(runtime.lifecycle().failureMessage(e));
        } finally {
            rememberRequest();
            runtime.monitorResultFuture = null;
            runtime.monitorCallback = null;
            if (runtime.sessionAlive
                    && !runtime.waitingForMonitor
                    && runtime.state != AgentState.PAUSED)
                runtime.lifecycle().transitionTo(AgentState.IDLE);
        }
    }

    void injectPendingTaskExitNotices() {
        if (runtime.backgroundTaskManager == null) {
            return;
        }
        if (runtime.monitorService != null) {
            runtime.backgroundTaskManager.drainExitNotices(runtime.agentId);
            return;
        }
        for (BackgroundTaskManager.TaskExitNotice notice :
                runtime.backgroundTaskManager.drainExitNotices(runtime.agentId)) {
            String prefix =
                    "[notice] background task " + notice.taskId() + " (" + notice.command() + ") ";
            String text =
                    switch (notice.cause()) {
                        case NATURAL ->
                                prefix
                                        + "exited on its own with code "
                                        + notice.exitCode()
                                        + (notice.exitCode() != 0
                                                ? " — a non-zero code means it crashed."
                                                : ".")
                                        + " Launch it again with run_task if needed.";
                        case AGENT_STOP ->
                                prefix
                                        + "was stopped by you (stop_task). It is no longer"
                                        + " running.";
                        case USER_STOP ->
                                prefix
                                        + "was stopped by the user. It is no longer running —"
                                        + " launch it again with run_task only if asked.";
                        case AUTO_KILL ->
                                prefix
                                        + "was auto-killed because its timeout elapsed. It is"
                                        + " no longer running.";
                        case SHUTDOWN ->
                                prefix
                                        + "was terminated during server/agent cleanup. It is"
                                        + " no longer running.";
                    };
            runtime.output().appendObservation("task_exited", text);
        }
    }
}
