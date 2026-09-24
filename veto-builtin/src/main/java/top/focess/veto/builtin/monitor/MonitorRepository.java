package top.focess.veto.builtin.monitor;

import java.util.List;
import org.jspecify.annotations.NonNull;

public interface MonitorRepository {
    @NonNull List<MonitorEntity> findAll();

    @NonNull MonitorEntity save(@NonNull MonitorEntity entity);
}
