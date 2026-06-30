package top.focess.veto.agent.loop;

import org.jspecify.annotations.NonNull;

/**
 * The terminal action — the sole valid program exit ( {@code STOP}). Multiple allowed (early
 * exits).
 */
public record StopAction(@NonNull String id, @NonNull String label, @NonNull String resultBinding)
        implements Action {

    public StopAction {
        // resultBinding nullable → scope.synthesize
    }
}
