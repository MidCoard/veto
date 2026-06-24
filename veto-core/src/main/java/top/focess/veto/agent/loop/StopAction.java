package top.focess.veto.agent.loop;

/**
 * The terminal action — the sole valid program exit ( {@code STOP}). Multiple allowed (early
 * exits).
 */
public record StopAction(String id, String label, String resultBinding) implements Action {

    public StopAction {
        // resultBinding nullable → scope.synthesize
    }
}
