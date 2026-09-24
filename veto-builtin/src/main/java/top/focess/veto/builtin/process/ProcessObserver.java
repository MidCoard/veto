package top.focess.veto.builtin.process;

import org.jspecify.annotations.NonNull;

/** Process facts; subscribers own any derived feature state and retry policy. */
public interface ProcessObserver {
    void changed(@NonNull String owner, @NonNull TaskInfo info, @NonNull String cause);
}
