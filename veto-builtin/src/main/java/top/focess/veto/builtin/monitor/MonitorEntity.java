package top.focess.veto.builtin.monitor;

import org.jspecify.annotations.NonNull;

public class MonitorEntity {
    private @NonNull String id = "";

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
