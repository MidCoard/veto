package top.focess.veto.model;

import java.time.Instant;
import java.util.*;

/** A request to execute a specific atomic tool capability within C6 Sandbox. */
public class ToolExecutionRequest {

    private final String id;
    private final String capabilityName;
    private final Map<String, Object> arguments;
    private final Set<String> requiredCredentials;
    private final String sessionId;
    private final String workflowId;
    private final Instant createdAt;
    private volatile ToolExecutionStatus status;
    private volatile String resultPayload;
    private volatile String errorMessage;

    public ToolExecutionRequest(String capabilityName, Map<String, Object> arguments) {
        this(UUID.randomUUID().toString(), capabilityName, arguments, Set.of(), "", "");
    }

    public ToolExecutionRequest(
            String id,
            String capabilityName,
            Map<String, Object> arguments,
            Set<String> requiredCredentials,
            String sessionId,
            String workflowId) {
        this.id = id;
        this.capabilityName = capabilityName;
        this.arguments =
                arguments != null
                        ? Collections.unmodifiableMap(new HashMap<>(arguments))
                        : Map.of();
        this.requiredCredentials =
                requiredCredentials != null
                        ? Collections.unmodifiableSet(new HashSet<>(requiredCredentials))
                        : Set.of();
        this.sessionId = sessionId;
        this.workflowId = workflowId;
        this.createdAt = Instant.now();
        this.status = ToolExecutionStatus.PENDING;
        this.resultPayload = null;
        this.errorMessage = null;
    }

    public String getId() {
        return id;
    }

    public String getCapabilityName() {
        return capabilityName;
    }

    public Map<String, Object> getArguments() {
        return arguments;
    }

    public Set<String> getRequiredCredentials() {
        return requiredCredentials;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getWorkflowId() {
        return workflowId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public synchronized ToolExecutionStatus getStatus() {
        return status;
    }

    public synchronized String getResultPayload() {
        return resultPayload;
    }

    public synchronized String getErrorMessage() {
        return errorMessage;
    }

    public synchronized void markCompleted(String result) {
        this.status = ToolExecutionStatus.COMPLETED;
        this.resultPayload = result;
    }

    public synchronized void markFailed(String error) {
        this.status = ToolExecutionStatus.FAILED;
        this.errorMessage = error;
    }

    public synchronized void markRunning() {
        this.status = ToolExecutionStatus.RUNNING;
    }

    public synchronized void markVetoed(String reason) {
        this.status = ToolExecutionStatus.VETOED;
        this.errorMessage = reason;
    }

    public enum ToolExecutionStatus {
        PENDING,
        RUNNING,
        COMPLETED,
        FAILED,
        VETOED
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ToolExecutionRequest)) return false;
        ToolExecutionRequest that = (ToolExecutionRequest) o;
        return id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "ToolExecutionRequest{id='"
                + id
                + "', capability='"
                + capabilityName
                + "', status="
                + status
                + "}";
    }
}
