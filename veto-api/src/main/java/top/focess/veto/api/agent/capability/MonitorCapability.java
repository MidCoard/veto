package top.focess.veto.api.agent.capability;

import java.time.Instant;
import org.jspecify.annotations.NonNull;

public interface MonitorCapability extends Capability {
    @NonNull Object create(@NonNull String purpose, @NonNull Instant due);

    @NonNull Object inspect();

    @NonNull Object control(@NonNull String id, @NonNull String operation);
}
