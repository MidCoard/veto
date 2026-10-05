package top.focess.veto.builtin.process;

import java.util.UUID;
import org.jspecify.annotations.NonNull;

/** Process facts; subscribers own any derived feature state and retry policy. */
public interface ProcessObserver {
    void changed(@NonNull UUID userId, @NonNull TaskInfo info, @NonNull String cause);
}
