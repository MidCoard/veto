package top.focess.veto.model;

import java.time.Instant;
import java.util.*;

/**
 * A DAG (Directed Acyclic Graph) task payload routed through C3 Communication Bus.
 * Each DAGPayload represents a node in the task graph with dependencies and execution context.
 */
public class DAGPayload {

    private final String id;
    private final String taskType;
    private final Map<String, Object> parameters;
    private final Set<String> dependencies;
    private final DAGPayloadStatus status;
    private final Instant createdAt;
    private final Instant updatedAt;
    private final String sourceComponent;
    private final String targetComponent;

    public DAGPayload(String id, String taskType, Map<String, Object> parameters,
                      Set<String> dependencies, String sourceComponent, String targetComponent) {
        this.id = id;
        this.taskType = taskType;
        this.parameters = parameters != null ? Collections.unmodifiableMap(new HashMap<>(parameters)) : Map.of();
        this.dependencies = dependencies != null ? Collections.unmodifiableSet(new HashSet<>(dependencies)) : Set.of();
        this.status = DAGPayloadStatus.PENDING;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
        this.sourceComponent = sourceComponent;
        this.targetComponent = targetComponent;
    }

    // Private constructor for builder/deserialization
    private DAGPayload(String id, String taskType, Map<String, Object> parameters,
                       Set<String> dependencies, DAGPayloadStatus status,
                       Instant createdAt, Instant updatedAt,
                       String sourceComponent, String targetComponent) {
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

    public String getId() { return id; }
    public String getTaskType() { return taskType; }
    public Map<String, Object> getParameters() { return parameters; }
    public Set<String> getDependencies() { return dependencies; }
    public DAGPayloadStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public String getSourceComponent() { return sourceComponent; }
    public String getTargetComponent() { return targetComponent; }

    public DAGPayload withStatus(DAGPayloadStatus newStatus) {
        Map<String, Object> newParams = new HashMap<>(parameters);
        return new DAGPayload(id, taskType, newParams, dependencies, newStatus, createdAt, Instant.now(), sourceComponent, targetComponent);
    }

    public DAGPayload withUpdatedParameters(Map<String, Object> newParams) {
        Map<String, Object> merged = new HashMap<>(this.parameters);
        merged.putAll(newParams);
        return new DAGPayload(id, taskType, merged, dependencies, status, createdAt, Instant.now(), sourceComponent, targetComponent);
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

        public Builder id(String id) { this.id = id; return this; }
        public Builder taskType(String taskType) { this.taskType = taskType; return this; }
        public Builder parameter(String key, Object value) { this.parameters.put(key, value); return this; }
        public Builder parameters(Map<String, Object> parameters) { this.parameters.putAll(parameters); return this; }
        public Builder dependency(String depId) { this.dependencies.add(depId); return this; }
        public Builder dependencies(Set<String> dependencies) { this.dependencies.addAll(dependencies); return this; }
        public Builder sourceComponent(String sourceComponent) { this.sourceComponent = sourceComponent; return this; }
        public Builder targetComponent(String targetComponent) { this.targetComponent = targetComponent; return this; }

        public DAGPayload build() {
            return new DAGPayload(
                id != null ? id : UUID.randomUUID().toString(),
                taskType, parameters, dependencies, sourceComponent, targetComponent
            );
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DAGPayload)) return false;
        DAGPayload that = (DAGPayload) o;
        return id.equals(that.id);
    }

    @Override
    public int hashCode() { return Objects.hash(id); }

    @Override
    public String toString() {
        return "DAGPayload{" +
            "id='" + id + '\'' +
            ", taskType='" + taskType + '\'' +
            ", status=" + status +
            ", source=" + sourceComponent +
            ", target=" + targetComponent +
            '}';
    }
}
