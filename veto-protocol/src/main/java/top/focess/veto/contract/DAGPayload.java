package top.focess.veto.contract;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/** Immutable DAG task value. Task identity remains its id; updates return new snapshots. */
public record DAGPayload(
        @JsonProperty(value = "id", required = true) @NonNull String id,
        @JsonProperty(value = "taskType", required = true) @NonNull String taskType,
        @JsonProperty(value = "parameters", required = true)
                @NonNull Map<String, Object> parameters,
        @JsonProperty(value = "dependencies", required = true) @NonNull Set<String> dependencies,
        @JsonProperty(value = "status", required = true) @NonNull DAGPayloadStatus status,
        @JsonProperty(value = "createdAt", required = true) @NonNull Instant createdAt,
        @JsonProperty(value = "updatedAt", required = true) @NonNull Instant updatedAt,
        String sourceComponent,
        String targetComponent) {
    public DAGPayload {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(taskType, "taskType");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        parameters = Collections.unmodifiableMap(new HashMap<>(parameters));
        dependencies = Collections.unmodifiableSet(new HashSet<>(dependencies));
    }

    public @NonNull DAGPayload withStatus(@NonNull DAGPayloadStatus next) {
        return new DAGPayload(
                id,
                taskType,
                parameters,
                dependencies,
                next,
                createdAt,
                Instant.now(),
                sourceComponent,
                targetComponent);
    }

    public @NonNull DAGPayload withUpdatedParameters(@NonNull Map<String, Object> changes) {
        var merged = new HashMap<>(parameters);
        merged.putAll(changes);
        return new DAGPayload(
                id,
                taskType,
                merged,
                dependencies,
                status,
                createdAt,
                Instant.now(),
                sourceComponent,
                targetComponent);
    }

    public enum DAGPayloadStatus {
        PENDING,
        RUNNING,
        COMPLETED,
        FAILED,
        VETOED,
        CANCELLED
    }

    public static @NonNull Builder builder() {
        return new Builder();
    }

    /** Fluent builder for {@link DAGPayload}; {@code taskType} is the only required field. */
    public static class Builder {
        private String id;
        private String taskType;
        private final @NonNull Map<String, Object> parameters = new HashMap<>();
        private final @NonNull Set<String> dependencies = new HashSet<>();
        private String sourceComponent;
        private String targetComponent;

        public @NonNull Builder id(@NonNull String id) {
            this.id = id;
            return this;
        }

        public @NonNull Builder taskType(@NonNull String taskType) {
            this.taskType = taskType;
            return this;
        }

        public @NonNull Builder parameter(@NonNull String key, @NonNull Object value) {
            this.parameters.put(key, value);
            return this;
        }

        public @NonNull Builder parameters(@NonNull Map<String, Object> parameters) {
            this.parameters.putAll(parameters);
            return this;
        }

        public @NonNull Builder dependency(@NonNull String depId) {
            this.dependencies.add(depId);
            return this;
        }

        public @NonNull Builder dependencies(@NonNull Set<String> dependencies) {
            this.dependencies.addAll(dependencies);
            return this;
        }

        public @NonNull Builder sourceComponent(@NonNull String sourceComponent) {
            this.sourceComponent = sourceComponent;
            return this;
        }

        public @NonNull Builder targetComponent(@NonNull String targetComponent) {
            this.targetComponent = targetComponent;
            return this;
        }

        /**
         * Build the payload; a missing id defaults to a random UUID.
         *
         * @throws IllegalStateException if no task type was set
         */
        public @NonNull DAGPayload build() {
            String requiredTaskType = taskType;
            if (requiredTaskType == null) {
                throw new IllegalStateException("DAGPayload taskType is required");
            }
            return new DAGPayload(
                    id != null ? id : UUID.randomUUID().toString(),
                    requiredTaskType,
                    parameters,
                    dependencies,
                    DAGPayloadStatus.PENDING,
                    Instant.now(),
                    Instant.now(),
                    sourceComponent,
                    targetComponent);
        }
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof DAGPayload payload && id.equals(payload.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public @NonNull String toString() {
        return "DAGPayload[id=" + id + ", taskType=" + taskType + ", status=" + status + "]";
    }
}
