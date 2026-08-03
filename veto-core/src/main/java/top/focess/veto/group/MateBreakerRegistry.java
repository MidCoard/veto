package top.focess.veto.group;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.loop.LoopBreaker;

/**
 * The per-Mate breaker registry (Part 2.5 — per-agent breakers, no group breaker). Each Mate in an
 * active group has its own {@link LoopBreaker} tracking the model-call count between two
 * dispatches. On trip, the Mate posts a terminal {@code STATUS} to the Leader and the orchestrator
 * re-assigns the node.
 *
 * <p>Replaced the earlier "group breaker" (iteration count + wall-clock + cumulative cost) with the
 * single per-agent metric; cumulative cost remains display-only.
 */
@Component
public class MateBreakerRegistry {

    private final @NonNull ConcurrentMap<UUID, ConcurrentMap<String, LoopBreaker>> breakers =
            new ConcurrentHashMap<>();

    /** Get-or-create the LoopBreaker for a Mate in a group. */
    public @NonNull LoopBreaker forMate(
            @NonNull UUID groupId, @NonNull String mateId, long maxCallsPerEpisode) {
        return breakers.computeIfAbsent(groupId, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(mateId, k -> new LoopBreaker(maxCallsPerEpisode));
    }

    /** Reset the breaker (called when a Mate receives a new dispatch from the Leader). */
    public void newEpisode(@NonNull UUID groupId, @NonNull String mateId) {
        LoopBreaker b = breaker(groupId, mateId);
        if (b != null) {
            b.newEpisode();
        }
    }

    /** Record one model call for a Mate. */
    public void recordModelCall(@NonNull UUID groupId, @NonNull String mateId) {
        LoopBreaker b = breaker(groupId, mateId);
        if (b != null) {
            b.recordModelCall();
        }
    }

    /** Check whether the Mate's per-episode ceiling has been reached. */
    public boolean shouldTrip(@NonNull UUID groupId, @NonNull String mateId) {
        LoopBreaker b = breaker(groupId, mateId);
        return b != null && b.shouldTrip();
    }

    /** Drop all breakers for a group (called when the group is disbanded). */
    public void clear(@NonNull UUID groupId) {
        breakers.remove(groupId);
    }

    /** Test-only: snapshot of all breakers for a group. */
    public Map<String, @NonNull LoopBreaker> snapshot(@NonNull UUID groupId) {
        ConcurrentMap<String, LoopBreaker> map = breakers.get(groupId);
        return map == null ? Map.of() : Map.copyOf(map);
    }

    private @Nullable LoopBreaker breaker(@NonNull UUID groupId, @NonNull String mateId) {
        ConcurrentMap<String, LoopBreaker> map = breakers.get(groupId);
        return map == null ? null : map.get(mateId);
    }
}
