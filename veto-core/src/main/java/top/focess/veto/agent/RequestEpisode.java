package top.focess.veto.agent;

import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.loop.LoopBreaker;

/** Task identity and cumulative model-call ledger shared by explicit continuation handles. */
final class RequestEpisode {
    private final @NonNull String id;
    private final @NonNull LoopBreaker breaker;
    private @NonNull String task = "";
    private String observationId;

    RequestEpisode(@NonNull String id, long maxCallsPerSegment) {
        this.id = id;
        this.breaker = new LoopBreaker(maxCallsPerSegment);
    }

    @NonNull String id() {
        return id;
    }

    @NonNull LoopBreaker breaker() {
        return breaker;
    }

    @NonNull String task() {
        return task;
    }

    void task(@NonNull String value) {
        task = value;
    }

    String observationId() {
        return observationId;
    }

    void observationId(String value) {
        observationId = value;
    }
}
