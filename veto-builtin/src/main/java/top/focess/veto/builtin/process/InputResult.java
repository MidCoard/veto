package top.focess.veto.builtin.process;

import org.jspecify.annotations.NonNull;

/** Outcome of a stdin queue attempt. */
public record InputResult(@NonNull InputStatus status, int bytes, boolean closeQueued) {
    /** Creates a failure result carrying the given status. */
    public static @NonNull InputResult failure(@NonNull InputStatus status) {
        return new InputResult(status, 0, false);
    }

    /** Whether the input was accepted and queued. */
    public boolean queued() {
        return status == InputStatus.QUEUED;
    }
}
