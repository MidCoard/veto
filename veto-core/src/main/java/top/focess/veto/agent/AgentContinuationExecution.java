package top.focess.veto.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.AgentRuntimeState.ActivatedObservation;
import top.focess.veto.agent.ExecutionControl.Wait;
import top.focess.veto.agent.continuation.RequestContinuationStore;
import top.focess.veto.api.agent.AgentAction;
import top.focess.veto.api.agent.AgentState;
import top.focess.veto.api.plugin.contract.AgentWorkSource;
import top.focess.veto.util.Nullness;
import top.focess.veto.vault.KeysteadVault;

/** Executes plugin-supplied observations under the existing request budget and lifecycle gates. */
final class AgentContinuationExecution {
    private static final int MAX_TRANSIENT_EPISODES = 256;
    private final @NonNull AgentRuntimeState runtime;
    /** Live request ledgers retained for late plugin observations during this agent lifetime. */
    private final @NonNull Map<String, RequestEpisode> episodes = new LinkedHashMap<>();

    AgentContinuationExecution(@NonNull AgentRuntimeState runtime) {
        this.runtime = runtime;
    }

    void attachExecutionVault(@NonNull KeysteadVault vault) {
        runtime.executionVault = vault;
    }

    void attachContinuationStore(@NonNull RequestContinuationStore store) {
        runtime.continuationStore = store;
    }

    RequestEpisode findContinuation(@NonNull String requestId) {
        RequestEpisode live = episodes.get(requestId);
        if (live != null) return live;
        RequestContinuationStore store = runtime.continuationStore;
        if (store != null) {
            var saved = store.load(runtime.sessionId, runtime.agentId, requestId).orElse(null);
            if (saved != null) {
                Long granted = saved.grantedCalls();
                var value = new RequestEpisode(requestId, runtime.maxCallsPerEpisode);
                value.task(saved.task());
                value.breaker()
                        .restore(
                                saved.consumedCalls(),
                                granted != null
                                        ? granted
                                        : runtime.maxCallsPerEpisode < 0
                                                ? -1
                                                : runtime.maxCallsPerEpisode);
                episodes.put(requestId, value);
                return value;
            }
        }
        return null;
    }

    @NonNull RequestEpisode newEpisode() {
        return new RequestEpisode(
                java.util.UUID.randomUUID().toString(), runtime.maxCallsPerEpisode);
    }

    void remember(@NonNull RequestEpisode episode) {
        episodes.putIfAbsent(episode.id(), episode);
        while (episodes.size() > MAX_TRANSIENT_EPISODES) {
            var oldest = episodes.entrySet().iterator();
            if (!oldest.hasNext()) break;
            oldest.next();
            oldest.remove();
        }
    }

    void settled(@NonNull RequestEpisode episode) {
        // Production can reload a late observation from its durable checkpoint. Embedded runners
        // without that store keep a bounded transient window for the same behavior.
        if (runtime.continuationStore != null) episodes.remove(episode.id(), episode);
    }

    void reserveRequestCall() {
        runtime.lifecycle().currentRequest().episode.breaker().recordModelCall();
        persistRequest();
    }

    void persistRequest() {
        RequestContinuationStore store = runtime.continuationStore;
        RequestHandle handle = runtime.control.request();
        if (store == null || handle == null) return;
        store.save(
                runtime.sessionId,
                runtime.agentId,
                handle.episode.id(),
                handle.episode.task(),
                handle.episode.breaker().count(),
                handle.episode.breaker().grantedCalls());
    }

    boolean belongsToActiveRequest(AgentWorkSource.@NonNull Observation event) {
        RequestHandle handle = runtime.control.request();
        if (handle == null) return false;
        String observationId = handle.episode.observationId();
        return observationId != null
                ? observationId.equals(event.id())
                : handle.episode.id().equals(event.requestId());
    }

    void attachWorkSource(@NonNull AgentWorkSource service) {
        runtime.workSource = service;
    }

    void signalWork() {
        if (runtime.control.open() && runtime.workQueued.compareAndSet(false, true))
            runtime.actionQueue.add(
                    new QueuedRequest(
                            new AgentAction.WorkAvailableAction(), new RequestHandle(runtime)));
    }

    @NonNull List<AgentWorkSource.Observation> pendingObservations(
            @NonNull AgentWorkSource service) {
        List<AgentWorkSource.Observation> eligible = new ArrayList<>();
        for (AgentWorkSource.Observation event : service.pending(scope())) {
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
                service.cancelled(scope(), event);
            } catch (RuntimeException error) {
                AgentRuntimeState.log.warn(
                        "Cancelled request observation {} awaits persistence", event.id(), error);
            }
        }
        return eligible;
    }

    boolean injectObservations() {
        AgentWorkSource service = source();
        if (service == null) return false;
        runtime.lifecycle().checkExecutionBoundary();
        boolean inserted = false;
        for (AgentWorkSource.Observation event : pendingObservations(service)) {
            if (!belongsToActiveRequest(event)) continue;
            boolean recorded =
                    runtime.output().history().stream()
                            .anyMatch(
                                    t ->
                                            event.id().equals(t.payload().get("eventId"))
                                                    || (event.attributes().get("legacyEventId")
                                                                    instanceof String legacyId
                                                            && legacyId.equals(
                                                                    t.payload().get("eventId"))));
            if (!recorded) {
                runtime.output()
                        .appendTurn(
                                new TurnRecord(
                                        runtime.output().nextTurn(),
                                        TurnType.RUNTIME_EVENT,
                                        attributes(event),
                                        event.occurredAt()));
            }
            service.started(scope(), event);
            runtime.activatedObservations.put(
                    event.id(),
                    new ActivatedObservation(
                            event, runtime.lifecycle().currentRequest().episode.id()));
            // A retried acknowledgement still needs reasoning, even if the history already exists.
            inserted = true;
        }
        return inserted;
    }

    void completeOrWaitForWork() {
        if (runtime.lifecycle().currentRequest().awaiting()) {
            runtime.lifecycle().saveExecutionWait(Wait.PLUGIN);
            runtime.lifecycle().transitionTo(AgentState.WAITING);
            signalWork();
        } else runtime.lifecycle().completeSuccess();
    }

    QueuedRequest claimWork() {
        KeysteadVault vault = runtime.executionVault;
        String executionOwner = runtime.owner;
        if (vault != null && (executionOwner == null || !vault.isUnlocked(executionOwner)))
            return null;
        AgentWorkSource service = source();
        if (runtime.control.waiting(Wait.INTERRUPTED)
                || runtime.control instanceof ExecutionControl.Suspended suspended
                        && !suspended.waits().equals(java.util.Set.of(Wait.PLUGIN))
                || runtime.control.waiting(Wait.BREAKER)
                || runtime.control.state() == AgentState.PAUSED
                || runtime.control.state() == AgentState.INTERCEPTED
                || !runtime.control.open()) return null;
        synchronized (runtime) {
            RequestHandle waiting = runtime.control.request();
            if (runtime.control.waiting(Wait.PLUGIN) && waiting != null && waiting.readyToResume())
                return new QueuedRequest(new AgentAction.WorkAction(), waiting);
            if (service == null) return null;
            // A newly submitted user task owns its own handoff future and goes first.
            if (runtime.actionQueue.stream()
                    .anyMatch(
                            a ->
                                    a.action() instanceof AgentAction.UserPromptAction
                                            || a.action()
                                                    instanceof AgentAction.DirectUserPromptAction))
                return null;
            var events = pendingObservations(service);
            if (events.isEmpty()) return null;
            if (runtime.control.waiting(Wait.PLUGIN)) {
                if (events.stream().noneMatch(this::belongsToActiveRequest)) return null;
            } else {
                AgentWorkSource.Observation first = null;
                for (AgentWorkSource.Observation candidate : events) {
                    String candidateOrigin = candidate.requestId();
                    if (candidateOrigin == null || findContinuation(candidateOrigin) != null) {
                        first = candidate;
                        break;
                    }
                }
                if (first == null) return null;
                String origin = first.requestId();
                String episodeId = first.continuationId();
                String requestId =
                        origin != null
                                ? origin
                                : episodeId != null ? episodeId : "observation:" + first.id();
                RequestEpisode continuation = findContinuation(requestId);
                if (origin != null && continuation == null) {
                    AgentRuntimeState.log.warn(
                            "Plugin work event {} awaits unavailable request context {}",
                            first.id(),
                            origin);
                    return null;
                }
                RequestEpisode episode =
                        continuation != null
                                ? continuation
                                : new RequestEpisode(requestId, runtime.maxCallsPerEpisode);
                if (continuation == null) episode.task(first.content());
                remember(episode);
                episode.observationId(origin == null ? first.id() : null);
                RequestHandle requestHandle = new RequestHandle(runtime, episode);
                runtime.control = runtime.control.withRequest(requestHandle);
                var listener = runtime.backgroundRequestListener;
                if (listener != null) listener.accept(requestHandle);
            }
            RequestHandle request = runtime.control.request();
            if (request == null)
                throw new IllegalStateException("Plugin work has no request owner");
            return new QueuedRequest(new AgentAction.WorkAction(), request);
        }
    }

    AgentWorkSource source() {
        if (runtime.workSource != null) return runtime.workSource;
        var selected = runtime.sessionPlugins;
        return selected == null ? null : selected.workSource(runtime.sessionId.toString());
    }

    AgentWorkSource.@NonNull Scope scope() {
        RequestHandle handle = runtime.control.request();
        return new AgentWorkSource.Scope(
                runtime.sessionId.toString(),
                runtime.agentId,
                handle == null ? null : handle.episode.id());
    }

    private @NonNull Map<String, Object> attributes(AgentWorkSource.@NonNull Observation event) {
        var attributes = new LinkedHashMap<String, Object>(event.attributes());
        attributes.put("eventId", event.id());
        attributes.put("topic", event.topic());
        attributes.put(
                "requestId",
                event.requestId() == null ? "" : Nullness.requireNonNull(event.requestId()));
        attributes.put("content", event.content());
        attributes.put("originatingTask", runtime.currentTask());
        return attributes;
    }
}
