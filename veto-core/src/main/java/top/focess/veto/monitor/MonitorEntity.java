package top.focess.veto.monitor;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.jspecify.annotations.NonNull;

@Entity
@Table(name = "agent_monitors")
public class MonitorEntity {
    @Id private @NonNull String id = "";

    @Column(columnDefinition = "TEXT", nullable = false)
    private @NonNull String payload = "";

    protected MonitorEntity() {}

    public MonitorEntity(@NonNull String id, @NonNull String payload) {
        this.id = id;
        this.payload = payload;
    }

    public @NonNull String getId() {
        return id;
    }

    public @NonNull String getPayload() {
        return payload;
    }
}
