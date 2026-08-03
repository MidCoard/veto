package top.focess.veto.agent.loop;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * The terminal action — the sole valid program exit ( {@code STOP}). Multiple allowed (early
 * exits).
 */
public record StopAction(@NonNull String id, @NonNull String label, @Nullable String resultBinding)
        implements Action {

    public StopAction {
        // resultBinding nullable → scope.synthesize
    }
}
