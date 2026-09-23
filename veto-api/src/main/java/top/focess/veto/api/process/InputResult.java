package top.focess.veto.api.process;

import java.time.*;
import java.util.*;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.tool.*;

public record InputResult(@NonNull InputStatus status, int bytes, boolean closeQueued) {
    public static @NonNull InputResult failure(@NonNull InputStatus status) {
        return new InputResult(status, 0, false);
    }

    public boolean queued() {
        return status == InputStatus.QUEUED;
    }
}
