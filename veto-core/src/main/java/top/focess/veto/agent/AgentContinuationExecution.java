package top.focess.veto.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.function.Supplier;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.focess.veto.agent.ExecutionControl.Wait;
import top.focess.veto.agent.continuation.RequestContinuationStore;
import top.focess.veto.api.agent.AgentAction;
import top.focess.veto.api.agent.AgentResult;
import top.focess.veto.api.llm.LlmBinding;
import top.focess.veto.api.plugin.contract.AgentInbox;
import top.focess.veto.integration.plugins.SessionPlugins;
import top.focess.veto.util.Nullness;
import top.focess.veto.vault.KeysteadVault;

/**
 * Owns continuation budgets and observation acknowledgements. Ledger/history operations, work
 * claiming and completion are confined to the Agent execution thread. This component returns
 * claimed work without publishing request/control transitions or invoking listeners.
 */
final class AgentContinuationExecution {
    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.agent.AgentContinuationExecution");
    private static final int MAX_TRANSIENT_EPISODES = 256;
    private final @NonNull String agentId;
    private final @NonNull UUID sessionId;
    private final String owner;
    private final long maxCallsPerEpisode;
    private final @NonNull AgentOutput output;
    private final @NonNull BlockingQueue<RunnerCommand> actionQueue;
    private final @NonNull Supplier<@Nullable SessionPlugins> sessionPlugins;
    private KeysteadVault executionVault;
    private RequestContinuationStore continuationStore;
    private AgentInbox workSource;
    private SessionPlugins workSourceSelection;
    private AgentInbox selectedWorkSource;

    /** Live request ledgers retained for late plugin observations during this agent lifetime. */
    private final @NonNull Map<String, RequestEpisode> episodes = new LinkedHashMap<>();

    private record ActivatedObservation(
            AgentInbox.@NonNull Observation event, @NonNull String requestId) {}

    private final @NonNull Map<String, ActivatedObservation> activatedObservations =
            new LinkedHashMap<>();

    AgentContinuationExecution(
            @NonNull String agentId,
            @NonNull UUID sessionId,
            String owner,
            long maxCallsPerEpisode,
            @NonNull AgentOutput output,
            @NonNull BlockingQueue<RunnerCommand> actionQueue,
            @NonNull Supplier<@Nullable SessionPlugins> sessionPlugins) {
        this.agentId = agentId;
        this.sessionId = sessionId;
        this.owner = owner;
        this.maxCallsPerEpisode = maxCallsPerEpisode;
        this.output = output;
        this.actionQueue = actionQueue;
        this.sessionPlugins = sessionPlugins;
    }

    void attachExecutionVault(@NonNull KeysteadVault vault) {
        executionVault = vault;
    }

    void attachContinuationStore(@NonNull RequestContinuationStore store) {
        continuationStore = store;
    }

    RequestEpisode findContinuation(@NonNull String requestId) {
        RequestEpisode live = episodes.get(requestId);
        if (live != null) return live;
        RequestContinuationStore store = continuationStore;
        if (store != null) {
            var saved = store.load(sessionId, agentId, requestId).orElse(null);
            if (saved != null) {
                Long granted = saved.grantedCalls();
                var value = new RequestEpisode(requestId, maxCallsPerEpisode);
                value.task(saved.task());
                value.breaker()
                        .restore(
                                saved.consumedCalls(),
                                granted != null
                                        ? granted
                                        : maxCallsPerEpisode < 0 ? -1 : maxCallsPerEpisode);
                episodes.put(requestId, value);
                return value;
            }
        }
        return null;
    }

    @NonNull RequestEpisode newEpisode() {
        return new RequestEpisode(UUID.randomUUID().toString(), maxCallsPerEpisode);
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
        // Durable checkpoints support late observations; embedded runners retain a bounded window.
        if (continuationStore != null) episodes.remove(episode.id(), episode);
    }

    void reserveRequestCall(@NonNull RequestHandle handle) {
        handle.episode.breaker().recordModelCall();
        persistRequest(handle);
    }

    void persistRequest(RequestHandle handle) {
        RequestContinuationStore store = continuationStore;
        if (store == null || handle == null) return;
        store.save(
                sessionId,
                agentId,
                handle.episode.id(),
                handle.episode.task(),
                handle.episode.breaker().count(),
                handle.episode.breaker().grantedCalls());
    }

    boolean belongsToActiveRequest(AgentInbox.@NonNull Observation event, RequestHandle handle) {
        if (handle == null) return false;
        String observationId = handle.episode.observationId();
        return observationId != null
                ? observationId.equals(event.id())
                : handle.episode.id().equals(event.requestId());
    }

    void attachWorkSource(@NonNull AgentInbox service) {
        workSource = service;
    }

    @NonNull List<AgentInbox.Observation> pendingObservations(
            @NonNull AgentInbox service, RequestHandle handle) {
        List<AgentInbox.Observation> eligible = new ArrayList<>();
        for (AgentInbox.Observation event : service.pending(scope(handle))) {
            String request = event.requestId();
            boolean cancelled =
                    request != null
                            && output.history().stream()
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
                service.cancelled(scope(handle), event);
            } catch (RuntimeException error) {
                log.warn("Cancelled request observation {} awaits persistence", event.id(), error);
            }
        }
        return eligible;
    }

    /** The caller checks the execution boundary before injecting into this request's history. */
    boolean injectObservations(@NonNull RequestHandle handle) {
        AgentInbox service = source();
        if (service == null) return false;
        boolean inserted = false;
        for (AgentInbox.Observation event : pendingObservations(service, handle)) {
            if (!belongsToActiveRequest(event, handle)) continue;
            boolean recorded =
                    output.history().stream()
                            .anyMatch(t -> event.id().equals(t.payload().get("eventId")));
            if (!recorded) {
                output.appendTurn(
                        new TurnRecord(
                                output.nextTurn(),
                                TurnType.RUNTIME_EVENT,
                                attributes(event, handle),
                                event.occurredAt()));
            }
            service.started(scope(handle), event);
            activatedObservations.put(
                    event.id(), new ActivatedObservation(event, handle.episode.id()));
            // A retried acknowledgement still needs reasoning, even if the history already exists.
            inserted = true;
        }
        return inserted;
    }

    void complete(@NonNull RequestHandle handle, @NonNull AgentResult result) {
        AgentInbox service = source();
        if (service == null) return;
        for (var entry : List.copyOf(activatedObservations.entrySet())) {
            ActivatedObservation observation = entry.getValue();
            if (!handle.episode.id().equals(observation.requestId())) continue;
            try {
                service.completed(scope(handle), observation.event(), result.success());
                activatedObservations.remove(entry.getKey());
            } catch (RuntimeException error) {
                log.warn("Plugin completion remains unacknowledged", error);
            }
        }
    }

    /** Called on the Agent execution thread with its current control snapshot. */
    QueuedRequest claimWork(
            @NonNull ExecutionControl control,
            @NonNull Object requestOwner,
            @NonNull LlmBinding binding,
            @NonNull Locale locale) {
        KeysteadVault vault = executionVault;
        if (vault != null && (owner == null || !vault.isUnlocked(owner))) return null;
        AgentInbox service = source();
        if (!control.open()
                || control instanceof ExecutionControl.Suspended suspended
                        && suspended.reason() != Wait.PLUGIN) return null;
        RequestHandle waiting = control.request();
        if (control.waiting(Wait.PLUGIN) && waiting != null && waiting.readyToResume())
            return new QueuedRequest(new AgentAction.WorkAction(), waiting);
        if (service == null) return null;
        // A newly submitted user task owns its own handoff future and goes first.
        if (actionQueue.stream()
                .anyMatch(
                        a ->
                                a instanceof QueuedRequest request
                                        && request.action()
                                                instanceof AgentAction.UserPromptAction))
            return null;
        var events = pendingObservations(service, waiting);
        if (events.isEmpty()) return null;
        if (control.waiting(Wait.PLUGIN)) {
            if (events.stream().noneMatch(event -> belongsToActiveRequest(event, waiting)))
                return null;
            if (waiting == null)
                throw new IllegalStateException("Plugin work has no request owner");
            return new QueuedRequest(new AgentAction.WorkAction(), waiting);
        }
        AgentInbox.Observation first = null;
        for (AgentInbox.Observation candidate : events) {
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
            log.warn(
                    "Plugin work event {} awaits unavailable request context {}",
                    first.id(),
                    origin);
            return null;
        }
        RequestEpisode episode =
                continuation != null
                        ? continuation
                        : new RequestEpisode(requestId, maxCallsPerEpisode);
        if (continuation == null) episode.task(first.content());
        remember(episode);
        episode.observationId(origin == null ? first.id() : null);
        return new QueuedRequest(
                new AgentAction.WorkAction(),
                new RequestHandle(requestOwner, episode, binding, locale));
    }

    AgentInbox source() {
        if (workSource != null) return workSource;
        var selected = sessionPlugins.get();
        if (selected == null) return null;
        if (selected != workSourceSelection) {
            selectedWorkSource = selected.workSource(sessionId.toString());
            workSourceSelection = selected;
        }
        return selectedWorkSource;
    }

    AgentInbox.@NonNull InboxContext scope(RequestHandle handle) {
        return new AgentInbox.InboxContext(
                sessionId.toString(), agentId, handle == null ? null : handle.episode.id());
    }

    private @NonNull Map<@NonNull String, @Nullable Object> attributes(
            AgentInbox.@NonNull Observation event, @NonNull RequestHandle handle) {
        var attributes = new LinkedHashMap<@NonNull String, @Nullable Object>(event.attributes());
        attributes.put("eventId", event.id());
        attributes.put("topic", event.topic());
        attributes.put(
                "requestId",
                event.requestId() == null ? "" : Nullness.requireNonNull(event.requestId()));
        attributes.put("content", event.content());
        attributes.put("originatingTask", handle.episode.task());
        return attributes;
    }
}
