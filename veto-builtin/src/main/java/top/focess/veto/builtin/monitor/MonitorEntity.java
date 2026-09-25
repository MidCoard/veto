package top.focess.veto.builtin.monitor;

import org.jspecify.annotations.NonNull;

/** Persisted monitor row: an id and the JSON payload of a {@link MonitorRecord}. */
public class MonitorEntity {
    private @NonNull String id = "";

    private @NonNull String payload = "";

    /** Persistence proxy constructor. */
    protected MonitorEntity() {}

    /** Creates a row with the given id and serialized payload. */
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
