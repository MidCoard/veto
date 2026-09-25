package top.focess.veto.builtin.monitor;

import java.util.List;
import org.jspecify.annotations.NonNull;

/** Persistence port for monitor rows. */
public interface MonitorRepository {
    /** Returns every stored monitor row. */
    @NonNull List<MonitorEntity> findAll();

    /** Inserts or updates the given row and returns its stored form. */
    @NonNull MonitorEntity save(@NonNull MonitorEntity entity);
}
