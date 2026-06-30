package top.focess.veto.model;

import java.time.Instant;
import java.util.*;
import org.jspecify.annotations.NonNull;

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
            String id,
            String taskType,
            Map<String, Object> parameters,
            Set<String> dependencies,
            String sourceComponent,
            String targetComponent) {
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
            String id,
            String taskType,
            Map<String, Object> parameters,
            Set<String> dependencies,
            DAGPayloadStatus status,
            Instant createdAt,
            Instant updatedAt,
            String sourceComponent,
            String targetComponent) {
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

    public String getId() {
        return id;
    }

    public String getTaskType() {
        return taskType;
    }

    public Map<String, Object> getParameters() {
        return parameters;
    }

    public Set<String> getDependencies() {
        return dependencies;
    }

    public DAGPayloadStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public String getSourceComponent() {
        return sourceComponent;
    }

    public String getTargetComponent() {
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

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String id;
        private String taskType;
        private Map<String, Object> parameters = new HashMap<>();
        private Set<String> dependencies = new HashSet<>();
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

        public DAGPayload build() {
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
    public String toString() {
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
