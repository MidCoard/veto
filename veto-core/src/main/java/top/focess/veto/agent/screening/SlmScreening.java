package top.focess.veto.agent.screening;

import org.jspecify.annotations.NonNull;

/** Advisory semantic judgment produced by the local screening model. */
public record SlmScreening(
        @NonNull Relevance relevance, @NonNull Danger danger, @NonNull String reason) {

    /** Safe degradation when the local model is absent or its output cannot be parsed. */
    public static @NonNull SlmScreening degraded() {
        return new SlmScreening(Relevance.HIGH, Danger.SAFE, "local SLM unavailable");
    }
}
