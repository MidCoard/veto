package top.focess.veto.model;

import java.time.Instant;
import java.util.*;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * A DAG (Directed Acyclic Graph) task payload routed through bus Communication Bus. Each DAGPayload
 * represents a node in the task graph with dependencies and execution context.
 */
public class DAGPayload {

    private final @NonNull String id;
    private final @NonNull String taskType;
    private final @NonNull Map<String, Object> parameters;
    private final @NonNull Set<String> dependencies;
    private final @NonNull DAGPayloadStatus status;
    private final @NonNull Instant createdAt;
    private final @NonNull Instant updatedAt;
    private final @NonNull String sourceComponent;
    private final @NonNull String targetComponent;

    public DAGPayload(
            @NonNull String id,
            @NonNull String taskType,
            @Nullable Map<String, Object> parameters,
            @Nullable Set<String> dependencies,
            @NonNull String sourceComponent,
            @NonNull String targetComponent) {
        this.id = id;
        this.taskType = taskType;
        this.parameters =
                parameters != null
                        ? Collections.unmodifiableMap(new HashMap<>(parameters))
                        : Map.of();
        this.dependencies =
                dependencies != null
                        ? Collections.unmodifiableSet(new HashSet<>(dependencies))
                        : Set.of();
        this.status = DAGPayloadStatus.PENDING;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
        this.sourceComponent = sourceComponent;
        this.targetComponent = targetComponent;
    }

    // Private constructor for builder/deserialization
    private DAGPayload(
            @NonNull String id,
            @NonNull String taskType,
            @NonNull Map<String, Object> parameters,
            @NonNull Set<String> dependencies,
            @NonNull DAGPayloadStatus status,
            @NonNull Instant createdAt,
            @NonNull Instant updatedAt,
            @NonNull String sourceComponent,
            @NonNull String targetComponent) {
        this.id = id;
        this.taskType = taskType;
        this.parameters = parameters;
        this.dependencies = dependencies;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.sourceComponent = sourceComponent;
        this.targetComponent = targetComponent;
    }

    public @NonNull String getId() {
        return id;
    }

    public @NonNull String getTaskType() {
        return taskType;
    }

    public @NonNull Map<String, Object> getParameters() {
        return parameters;
    }

    public @NonNull Set<String> getDependencies() {
        return dependencies;
    }

    public @NonNull DAGPayloadStatus getStatus() {
        return status;
    }

    public @NonNull Instant getCreatedAt() {
        return createdAt;
    }

    public @NonNull Instant getUpdatedAt() {
        return updatedAt;
    }

    public @NonNull String getSourceComponent() {
        return sourceComponent;
    }

    public @NonNull String getTargetComponent() {
        return targetComponent;
    }

    public @NonNull DAGPayload withStatus(@NonNull DAGPayloadStatus newStatus) {
        Map<String, Object> newParams = new HashMap<>(parameters);
        return new DAGPayload(
                id,
                taskType,
                newParams,
                dependencies,
                newStatus,
                createdAt,
                Instant.now(),
                sourceComponent,
                targetComponent);
    }

    public @NonNull DAGPayload withUpdatedParameters(@NonNull Map<String, Object> newParams) {
        Map<String, Object> merged = new HashMap<>(this.parameters);
        merged.putAll(newParams);
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

    public static class Builder {
        private @Nullable String id;
        private @Nullable String taskType;
        private @NonNull Map<String, Object> parameters = new HashMap<>();
        private @NonNull Set<String> dependencies = new HashSet<>();
        private @Nullable String sourceComponent;
        private @Nullable String targetComponent;

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

        public @NonNull DAGPayload build() {
            return new DAGPayload(
                    id != null ? id : UUID.randomUUID().toString(),
                    taskType,
                    parameters,
                    dependencies,
                    sourceComponent,
                    targetComponent);
        }
    }

    @Override
    public boolean equals(@NonNull Object o) {
        if (this == o) return true;
        if (!(o instanceof DAGPayload)) return false;
        DAGPayload that = (DAGPayload) o;
        return id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public @NonNull String toString() {
        return "DAGPayload{"
                + "id='"
                + id
                + '\''
                + ", taskType='"
                + taskType
                + '\''
                + ", status="
                + status
                + ", source="
                + sourceComponent
                + ", target="
                + targetComponent
                + '}';
    }
}
