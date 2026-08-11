package top.focess.veto.agent.loop;

import java.util.Locale;
import org.jspecify.annotations.NonNull;
import top.focess.veto.i18n.Msg;

/**
 * The single-agent circuit breaker. One metric: <b>model calls between two {@code
 * UserPromptAction}s</b> (a model call = one {@code VetoResponse} in autonomous mode, one {@code
 * generate} action in guided mode; {@code tool}/{@code goto}/{@code conditional_goto}/{@code STOP}
 * are zero-call and don't increment). Per-episode, self-count only.
 *
 * <p>On a trip the agent transitions to {@code IDLE} (a trip is exactly an idle — not a distinct
 * state) and emits a notice; resumption is just a {@code UserPromptAction} ("continue"), which
 * resets the counter (new episode). Configurable; {@code maxCallsPerEpisode < 0} = infinite (a
 * life-long agent).
 */
public final class LoopBreaker {

    private final long maxCallsPerEpisode;
    private long count;

    /**
     * @param maxCallsPerEpisode the model-call ceiling between two user prompts; {@code < 0} =
     *     infinite (never trips).
     */
    public
    @NonNull
    LoopBreaker(long maxCallsPerEpisode) {
        this.maxCallsPerEpisode = maxCallsPerEpisode;
    }

    /** A fresh {@code UserPromptAction} starts a new episode — reset the counter. */
    public void newEpisode() {
        count = 0;
    }

    /** Whether the per-episode ceiling has been reached (checked at the top of each iteration). */
    public boolean shouldTrip() {
        return maxCallsPerEpisode >= 0 && count >= maxCallsPerEpisode;
    }

    /** Records one model call (autonomous {@code VetoResponse} or guided {@code generate}). */
    public void recordModelCall() {
        count++;
    }

    public long count() {
        return count;
    }

    public long maxCallsPerEpisode() {
        return maxCallsPerEpisode;
    }

    /** The notice emitted on a trip, in the session's message locale. */
    public static @NonNull String tripNotice(@NonNull Locale locale) {
        return Msg.get(locale, "error.agent.loopTripped");
    }
}
