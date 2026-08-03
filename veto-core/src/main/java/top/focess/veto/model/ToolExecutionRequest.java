package top.focess.veto.model;

import java.time.Instant;
import java.util.*;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/** A request to execute a specific atomic tool capability within sandbox Sandbox. */
public class ToolExecutionRequest {

    private final @NonNull String id;
    private final @NonNull String capabilityName;
    private final @NonNull Map<String, Object> arguments;
    private final @NonNull Set<String> requiredCredentials;
    private final @NonNull String sessionId;
    private final @NonNull String workflowId;
    private final @NonNull Instant createdAt;
    private volatile @NonNull ToolExecutionStatus status;
    private volatile @Nullable String resultPayload;
    private volatile @Nullable String errorMessage;

    public
    @NonNull
    ToolExecutionRequest(@NonNull String capabilityName, @NonNull Map<String, Object> arguments) {
        this(UUID.randomUUID().toString(), capabilityName, arguments, Set.of(), "", "");
    }

    public ToolExecutionRequest(
            @NonNull String id,
            @NonNull String capabilityName,
            @Nullable Map<String, Object> arguments,
            @Nullable Set<String> requiredCredentials,
            @NonNull String sessionId,
            @NonNull String workflowId) {
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

    public @NonNull String getId() {
        return id;
    }

    public @NonNull String getCapabilityName() {
        return capabilityName;
    }

    public @NonNull Map<String, Object> getArguments() {
        return arguments;
    }

    public @NonNull Set<String> getRequiredCredentials() {
        return requiredCredentials;
    }

    public @NonNull String getSessionId() {
        return sessionId;
    }

    public @NonNull String getWorkflowId() {
        return workflowId;
    }

    public @NonNull Instant getCreatedAt() {
        return createdAt;
    }

    public synchronized @NonNull ToolExecutionStatus getStatus() {
        return status;
    }

    public synchronized @Nullable String getResultPayload() {
        return resultPayload;
    }

    public synchronized @Nullable String getErrorMessage() {
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
    public @NonNull String toString() {
        return "ToolExecutionRequest{id='"
                + id
                + "', capability='"
                + capabilityName
                + "', status="
                + status
                + "}";
    }
}
