package top.focess.veto.builtin.monitor;

import java.util.List;
import org.jspecify.annotations.NonNull;

/**
 * Persistence port for monitor rows.
 *
 * <p>MonitorService invokes this port while holding its state monitor; implementations must not
 * call back into the service from another thread and wait for that thread. The port provides no
 * shared locking or transaction boundary itself. Implementations exposed to additional callers must
 * serialize their own mutable state and coordinate revision conflicts in storage.
 */
public interface MonitorRepository {
    /** Returns every stored monitor row. */
    @NonNull List<MonitorEntity> findAll();

    /** Inserts or updates the given row and returns its stored form. */
    @NonNull MonitorEntity save(@NonNull MonitorEntity entity);
}
