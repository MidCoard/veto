package top.focess.veto.monitor;

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

    protected RequestContinuationEntity() {}

    public RequestContinuationEntity(@NonNull String id, @NonNull String task, long consumedCalls) {
        if (consumedCalls < 0) throw new IllegalArgumentException("Negative request call count");
        this.id = id;
        this.task = task;
        this.consumedCalls = consumedCalls;
    }

    public @NonNull String getId() {
        return id;
    }

    public @NonNull String getTask() {
        return task;
    }

    public long getConsumedCalls() {
        return consumedCalls;
    }
}
