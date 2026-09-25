package top.focess.veto.agent.intercept;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.Instant;
import org.jspecify.annotations.NonNull;

/** Approval facts; contains no runner checkpoint or pending future. */
@Entity
@Table(name = "hitl_records")
public class HitlRecordEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private @NonNull String sessionId = "";
    private @NonNull String agentId = "";
    private @NonNull String callId = "";
    private @NonNull String event = "";
    private @NonNull String decision = "";
    private @NonNull String source = "";
    @Lob private @NonNull String grantJson = "";
    private @NonNull Instant createdAt = Instant.now();

    /** JPA no-arg constructor. */
    protected HitlRecordEntity() {}

    /** Constructs one history row for a single HITL event. */
    public HitlRecordEntity(
            @NonNull String sessionId,
            @NonNull String agentId,
            @NonNull String callId,
            @NonNull String event,
            @NonNull String decision,
            @NonNull String source,
            @NonNull String grantJson) {
        this.sessionId = sessionId;
        this.agentId = agentId;
        this.callId = callId;
        this.event = event;
        this.decision = decision;
        this.source = source;
        this.grantJson = grantJson;
    }

    public @NonNull String getEvent() {
        return event;
    }

    public @NonNull String getSessionId() {
        return sessionId;
    }

    public @NonNull String getAgentId() {
        return agentId;
    }

    public void setCreatedAt(@NonNull Instant createdAt) {
        this.createdAt = createdAt;
    }

    public @NonNull String getGrantJson() {
        return grantJson;
    }

    public @NonNull String getCallId() {
        return callId;
    }

    public @NonNull String getDecision() {
        return decision;
    }

    public @NonNull String getSource() {
        return source;
    }

    public @NonNull Instant getCreatedAt() {
        return createdAt;
    }
}
