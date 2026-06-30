package top.focess.veto.model;

import java.time.Instant;
import java.util.*;
import org.jspecify.annotations.NonNull;

/** A request to execute a specific atomic tool capability within sandbox Sandbox. */
public class ToolExecutionRequest {

    private final @NonNull String id;
    private final @NonNull String capabilityName;
    private final @NonNull Map<String, Object> arguments;
    private final @NonNull Set<String> requiredCredentials;
    private final @NonNull String sessionId;
    private final @NonNull String workflowId;
    private final @NonNull Instant createdAt;
    private volatile ToolExecutionStatus status;
    private volatile String resultPayload;
    private volatile String errorMessage;

    public
    @NonNull
    ToolExecutionRequest(@NonNull String capabilityName, @NonNull Map<String, Object> arguments) {
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

    public synchronized void markCompleted(@NonNull String result) {
        this.status = ToolExecutionStatus.COMPLETED;
        this.resultPayload = result;
    }

    public synchronized void markFailed(@NonNull String error) {
        this.status = ToolExecutionStatus.FAILED;
        this.errorMessage = error;
    }

    public synchronized void markRunning() {
        this.status = ToolExecutionStatus.RUNNING;
    }

    public synchronized void markVetoed(@NonNull String reason) {
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
    public boolean equals(@NonNull Object o) {
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
