package top.focess.veto.builtin.monitor;

import java.time.Instant;
import org.jspecify.annotations.NonNull;

/** Host capability backing the monitor tools. */
public interface MonitorOperations {
    /** Creates a monitor due at the given instant; returns the created record. */
    @NonNull Object create(@NonNull String purpose, @NonNull Instant due);

    /** Returns the monitors visible to the calling agent. */
    @NonNull Object inspect();

    /** Applies a control operation (pause, resume, cancel) to the given monitor. */
    @NonNull Object control(@NonNull String id, @NonNull String operation);
}
