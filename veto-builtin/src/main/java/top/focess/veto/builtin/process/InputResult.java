package top.focess.veto.builtin.process;

import org.jspecify.annotations.NonNull;

public record InputResult(@NonNull InputStatus status, int bytes, boolean closeQueued) {
    public static @NonNull InputResult failure(@NonNull InputStatus status) {
        return new InputResult(status, 0, false);
    }

    public boolean queued() {
        return status == InputStatus.QUEUED;
    }
}
