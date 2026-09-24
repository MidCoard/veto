package top.focess.veto.api.plugin.contract;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/** Plugin-owned inbox and completion policy; the host only executes admitted continuations. */
public interface AgentWorkSource {
    record Scope(@NonNull String sessionId, @NonNull String agentId, @Nullable String requestId) {}

    /**
     * continuationId is an opaque plugin-local durable key; the host namespaces it. Null uses the
     * namespaced observation identity.
     */
    record Observation(
            @NonNull String id,
            @Nullable String requestId,
            @NonNull String content,
            @NonNull Instant occurredAt,
            @NonNull String topic,
            @NonNull Map<String, Object> attributes,
            @Nullable String continuationId) {
        public Observation {
            attributes = Map.copyOf(attributes);
        }
    }

    @NonNull List<Observation> pending(@NonNull Scope scope);

    void started(@NonNull Scope scope, @NonNull Observation observation);

    void completed(@NonNull Scope scope, @NonNull Observation observation, boolean success);

    void cancelled(@NonNull Scope scope, @NonNull Observation observation);
}
