package top.focess.veto.agent.loop;

import java.util.Locale;
import org.jspecify.annotations.NonNull;
import top.focess.veto.i18n.Msg;

/** Core-owned cumulative consumption and explicitly granted allowance for one request. */
public final class LoopBreaker {

    private final long maxCallsPerEpisode;
    private long count;
    private long grantedCalls;
    // The parent may query its isolated child while it runs; publish one consistent answer.
    private volatile boolean exhausted;

    /**
     * @param maxCallsPerEpisode the model-call allowance per explicitly authorized segment; {@code
     *     < 0} = infinite (never trips).
     */
    public LoopBreaker(long maxCallsPerEpisode) {
        this.maxCallsPerEpisode = maxCallsPerEpisode;
        newEpisode();
    }

    /** Starts a new request with one initial allowance. Never use for request restoration. */
    public void newEpisode() {
        count = 0;
        grantedCalls = maxCallsPerEpisode < 0 ? -1 : maxCallsPerEpisode;
        exhausted = grantedCalls == 0;
    }

    /** Explicit user authorization after exhaustion adds one segment without erasing usage. */
    public void grantContinuation() {
        if (!shouldTrip()) throw new IllegalStateException("Request budget is not exhausted");
        grantedCalls = maxCallsPerEpisode < 0 ? -1 : saturatedAdd(grantedCalls, maxCallsPerEpisode);
        exhausted = grantedCalls >= 0 && count >= grantedCalls;
    }

    /** Legacy checkpoints carry only consumption and retain the original single-segment limit. */
    public void restoreCount(long consumedCalls) {
        restore(consumedCalls, maxCallsPerEpisode < 0 ? -1 : maxCallsPerEpisode);
    }

    /** Restores both consumed calls and granted allowance from a checkpoint. */
    public void restore(long consumedCalls, long allowance) {
        if (consumedCalls < 0 || allowance < -1)
            throw new IllegalArgumentException("Invalid model-call checkpoint");
        count = consumedCalls;
        grantedCalls = allowance;
        exhausted = grantedCalls >= 0 && count >= grantedCalls;
    }

    private static long saturatedAdd(long first, long second) {
        return first > Long.MAX_VALUE - second ? Long.MAX_VALUE : first + second;
    }

    public long grantedCalls() {
        return grantedCalls;
    }

    /**
     * Whether the total request allowance has been reached (checked at the top of each iteration).
     */
    public boolean shouldTrip() {
        return exhausted;
    }

    /** Records one model call (autonomous {@code VetoResponse} or plan {@code generate}). */
    public void recordModelCall() {
        count = saturatedAdd(count, 1);
        exhausted = grantedCalls >= 0 && count >= grantedCalls;
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
