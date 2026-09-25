package top.focess.veto.api.plugin.contract;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/** Plugin-owned inbox and completion policy; the host only executes admitted continuations. */
public interface AgentWorkSource {
    /**
     * Host-selected inbox scope.
     *
     * @param sessionId selected session
     * @param agentId selected agent
     * @param requestId active durable request, or {@code null} when polling outside a request
     */
    record Scope(@NonNull String sessionId, @NonNull String agentId, @Nullable String requestId) {}

    /**
     * continuationId is an opaque plugin-local durable key; the host namespaces it. Null uses the
     * namespaced observation identity.
     *
     * @param id plugin-local durable observation ID
     * @param requestId existing request correlation, or {@code null}
     * @param content observation text
     * @param occurredAt source event time
     * @param topic plugin-defined topic
     * @param attributes immutable plugin-defined attributes copied on construction
     * @param continuationId optional plugin-local continuation key
     */
    record Observation(
            @NonNull String id,
            @Nullable String requestId,
            @NonNull String content,
            @NonNull Instant occurredAt,
            @NonNull String topic,
            @NonNull Map<String, Object> attributes,
            @Nullable String continuationId) {
        /** Defensively copies the plugin-defined attributes. */
        public Observation {
            attributes = Map.copyOf(attributes);
        }
    }

    /**
     * Returns currently pending observations for the selected scope.
     *
     * @param scope host-selected inbox scope
     * @return immutable pending observations
     */
    @NonNull List<Observation> pending(@NonNull Scope scope);

    /**
     * Records that the host admitted and began the observation.
     *
     * @param scope host-selected inbox scope
     * @param observation admitted observation
     */
    void started(@NonNull Scope scope, @NonNull Observation observation);

    /**
     * Records terminal completion with the actual host success outcome.
     *
     * @param scope host-selected inbox scope
     * @param observation completed observation
     * @param success actual host execution outcome
     */
    void completed(@NonNull Scope scope, @NonNull Observation observation, boolean success);

    /**
     * Records cancellation before terminal completion.
     *
     * @param scope host-selected inbox scope
     * @param observation cancelled observation
     */
    void cancelled(@NonNull Scope scope, @NonNull Observation observation);
}
