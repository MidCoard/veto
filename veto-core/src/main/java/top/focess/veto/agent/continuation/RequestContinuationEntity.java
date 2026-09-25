package top.focess.veto.agent.continuation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.jspecify.annotations.NonNull;

/** The last committed reasoning budget reservation for one originating request. */
@Entity
@Table(name = "agent_request_continuations")
public class RequestContinuationEntity {
    @Id
    @Column(length = 1024)
    private @NonNull String id = "";

    @Column(columnDefinition = "TEXT", nullable = false)
    private @NonNull String task = "";

    @Column(nullable = false)
    private long consumedCalls;

    private Long grantedCalls;

    /** JPA proxy constructor. */
    protected RequestContinuationEntity() {}

    /** Creates a row with no granted-call allowance. */
    public RequestContinuationEntity(@NonNull String id, @NonNull String task, long consumedCalls) {
        this(id, task, consumedCalls, null);
    }

    /** Creates a row, rejecting negative consumed counts and allowances below -1. */
    public RequestContinuationEntity(
            @NonNull String id, @NonNull String task, long consumedCalls, Long grantedCalls) {
        if (grantedCalls != null && grantedCalls < -1)
            throw new IllegalArgumentException("Invalid request allowance");
        if (consumedCalls < 0) throw new IllegalArgumentException("Negative request call count");
        this.id = id;
        this.task = task;
        this.consumedCalls = consumedCalls;
        this.grantedCalls = grantedCalls;
    }

    public @NonNull String getId() {
        return id;
    }

    public @NonNull String getTask() {
        return task;
    }

    public Long getGrantedCalls() {
        return grantedCalls;
    }

    public long getConsumedCalls() {
        return consumedCalls;
    }
}
