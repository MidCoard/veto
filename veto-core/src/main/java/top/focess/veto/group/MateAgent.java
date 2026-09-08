package top.focess.veto.group;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.focess.veto.agent.Agent;
import top.focess.veto.agent.AgentAction;
import top.focess.veto.agent.AgentResult;
import top.focess.veto.agent.AgentState;
import top.focess.veto.agent.TurnRecord;
import top.focess.veto.agent.loop.LoopBreaker;

/**
 * Consumes {@code TASK_DISPATCH} messages from the Blackboard, runs the underlying {@link Agent},
 * and posts results back to the Leader.
 *
 * <p>Per-mate lifecycle:
 *
 * <ol>
 *   <li>{@link #start()} — starts a background scheduler that polls the Blackboard for new messages
 *       addressed to this Mate (default 200ms cadence).
 *   <li>On a {@code TASK_DISPATCH} message: parse the instruction, submit it as a {@link
 *       AgentAction.UserPromptAction} to the wrapped Agent, await the result.
 *   <li>On result success: post the Mate's actual final report in an {@code ACCEPT} message. The
 *       wrapper never fabricates an artifact path. If the agent's per-episode call ceiling tripped,
 *       post a terminal {@code STATUS} instead.
 *   <li>On result failure: post {@code FEEDBACK} with the failure reason.
 * </ol>
 *
 * <p>Each Mate has its own per-episode call ceiling via the {@link LoopBreaker} (registered with
 * the {@link MateBreakerRegistry}). On trip, the Mate posts a terminal {@code STATUS} and pauses
 * until the Leader re-dispatches.
 */
public class MateAgent {

    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.group.MateAgent");

    private final @NonNull String mateId;
    private final @NonNull UUID groupId;
    private final @NonNull Agent agent;
    private final @NonNull Blackboard blackboard;
    private final @NonNull MateBreakerRegistry breakers;
    private final long pollIntervalMs;
    private final long resultPollIntervalMs;
    private final @NonNull String skillset;

    /** Set of turnSeqs we have already processed (so we don't re-dispatch on each tick). */
    private final @NonNull ConcurrentMap<String, Long> lastSeenSeqByReceiver =
            new ConcurrentHashMap<>();

    private final @NonNull AtomicBoolean running = new AtomicBoolean(false);
    private final @NonNull ScheduledExecutorService scheduler;
    private ScheduledFuture<?> pollTask;

    public MateAgent(
            @NonNull String mateId,
            @NonNull UUID groupId,
            @NonNull String skillset,
            @NonNull Agent agent,
            @NonNull Blackboard blackboard,
            @NonNull MateBreakerRegistry breakers,
            long maxCallsPerEpisode) {
        this(
                mateId,
                groupId,
                skillset,
                agent,
                blackboard,
                breakers,
                maxCallsPerEpisode,
                200,
                1_000);
    }

    public MateAgent(
            @NonNull String mateId,
            @NonNull UUID groupId,
            @NonNull String skillset,
            @NonNull Agent agent,
            @NonNull Blackboard blackboard,
            @NonNull MateBreakerRegistry breakers,
            long maxCallsPerEpisode,
            long pollIntervalMs,
            long resultPollIntervalMs) {
        this.mateId = mateId;
        this.groupId = groupId;
        this.skillset = skillset;
        this.agent = agent;
        this.blackboard = blackboard;
        this.breakers = breakers;
        this.pollIntervalMs = pollIntervalMs;
        this.resultPollIntervalMs = resultPollIntervalMs;
        this.scheduler =
                Executors.newSingleThreadScheduledExecutor(
                        r -> {
                            Thread t = new Thread(r, "mate-" + mateId);
                            t.setDaemon(true);
                            return t;
                        });
        // Pre-register the breaker so the engine can read it.
        breakers.forMate(groupId, mateId, maxCallsPerEpisode);
    }

    public @NonNull String mateId() {
        return mateId;
    }

    public @NonNull String skillset() {
        return skillset;
    }

    public @NonNull Agent agent() {
        return agent;
    }

    public @NonNull UUID groupId() {
        return groupId;
    }

    /** Start polling the Blackboard for messages addressed to this Mate. */
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        log.info("MateAgent[{}] starting (skillset={}, group={})", mateId, skillset, groupId);
        pollTask =
                scheduler.scheduleAtFixedRate(this::poll, 0, pollIntervalMs, TimeUnit.MILLISECONDS);
    }

    /** Stop polling, interrupt the result waiter, and terminate the underlying agent. */
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        agent.terminate();
        if (pollTask != null) {
            pollTask.cancel(true);
        }
        scheduler.shutdownNow();
        try {
            if (!scheduler.awaitTermination(2_000L, TimeUnit.MILLISECONDS)) {
                log.warn("MateAgent[{}] result waiter did not stop within 2 seconds", mateId);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void poll() {
        try {
            String key = lastSeenSeqKey();
            long seen = lastSeenSeqByReceiver.getOrDefault(key, 0L);
            List<BlackboardMessage> newMessages = newMessagesSince(seen);
            for (BlackboardMessage m : newMessages) {
                if (!running.get()) {
                    break;
                }
                lastSeenSeqByReceiver.put(key, m.turnSeq());
                if (m.type() == BlackboardMessage.MessageType.TASK_DISPATCH) {
                    handleDispatch(m);
                }
            }
        } catch (Throwable t) {
            log.error("MateAgent[{}] poll failed", mateId, t);
        }
    }

    private @NonNull List<BlackboardMessage> newMessagesSince(long seen) {
        return blackboard.readAll(groupId).stream()
                .filter(m -> mateId.equals(m.receiverId()))
                .filter(m -> m.turnSeq() > seen)
                .toList();
    }

    private void handleDispatch(@NonNull BlackboardMessage dispatch) {
        // 1. Parse nodeId + instruction from the payload (format: "<nodeId>:<instruction>").
        String payload = dispatch.payload();
        int colon = payload.indexOf(':');
        if (colon < 0) {
            postFeedback(dispatch.payload(), "malformed dispatch payload");
            return;
        }
        String nodeId = payload.substring(0, colon).strip();
        String instruction = payload.substring(colon + 1).strip();

        // 2. Check the Mate's per-episode breaker.
        if (breakers.shouldTrip(groupId, mateId)) {
            log.warn("MateAgent[{}] breaker tripped on dispatch of node {}", mateId, nodeId);
            postTerminalStatus(nodeId, "breaker-tripped");
            return;
        }

        // 3. Reset the breaker for the new episode and submit the task.
        breakers.newEpisode(groupId, mateId);
        log.info("MateAgent[{}] dispatching node {}: {}", mateId, nodeId, instruction);
        agent.submit(instruction);

        // Await the actual task result. A polling deadline is not a task failure: a Mate
        // can legitimately need several model calls, tool waits, or user approvals.
        while (running.get()) {
            try {
                AgentResult result = agent.await(Duration.ofMillis(resultPollIntervalMs));
                if (!running.get()) {
                    return;
                }
                breakers.recordModelCall(groupId, mateId);
                if (result.success()) {
                    postAccept(nodeId, result.message());
                } else {
                    postFeedback(nodeId, result.message());
                }
                return;
            } catch (TimeoutException e) {
                // Check lifecycle state, then continue waiting for this same task.
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                if (running.get()) {
                    postFeedback(nodeId, "result wait interrupted");
                }
                return;
            } catch (RuntimeException e) {
                if (running.get()) {
                    postFeedback(nodeId, "result wait failed: " + e.getMessage());
                }
                return;
            }
        }
    }

    private void postAccept(@NonNull String nodeId, @NonNull String summary) {
        String encoded =
                Base64.getEncoder()
                        .encodeToString(summary.strip().getBytes(StandardCharsets.UTF_8));
        String payload = nodeId + ":accept-base64:" + encoded;
        blackboard.post(
                new BlackboardMessage(
                        UUID.randomUUID().toString(),
                        groupId,
                        mateId,
                        "LEADER",
                        BlackboardMessage.MessageType.ACCEPT,
                        payload,
                        0));
    }

    private void postFeedback(@NonNull String nodeId, String reason) {
        blackboard.post(
                new BlackboardMessage(
                        UUID.randomUUID().toString(),
                        groupId,
                        mateId,
                        "LEADER",
                        BlackboardMessage.MessageType.FEEDBACK,
                        nodeId + ":feedback:" + (reason == null ? "unknown" : reason),
                        0));
    }

    private void postTerminalStatus(@NonNull String nodeId, @NonNull String reason) {
        blackboard.post(
                new BlackboardMessage(
                        UUID.randomUUID().toString(),
                        groupId,
                        mateId,
                        "LEADER",
                        BlackboardMessage.MessageType.STATUS,
                        "terminal:" + nodeId + ":" + reason,
                        0));
    }

    private static @NonNull String lastSeenSeqKey() {
        // Single Mate per MateAgent instance; the key is just the bare Mate id. (Per-group
        // dedupe is implicit because we only ever read this group's Blackboard.)
        return "self";
    }

    /** The Mate's current agent state. */
    public @NonNull AgentState state() {
        return agent.state();
    }

    /** Returns the Mate's turn history. */
    public @NonNull List<TurnRecord> history() {
        return agent.history();
    }
}
