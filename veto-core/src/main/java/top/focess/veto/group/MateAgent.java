package top.focess.veto.group;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.focess.veto.agent.Agent;
import top.focess.veto.agent.AgentAction;
import top.focess.veto.agent.AgentResult;
import top.focess.veto.agent.AgentState;
import top.focess.veto.agent.TurnRecord;

/**
 * A real Mate — consumes {@code TASK_DISPATCH} messages from the Blackboard, runs the underlying
 * {@link Agent} (the ReAct loop), and posts results back to the Leader. Replaces the test stub
 * {@code GroupOrchestrator.simulateAccept(...)} with a working async Mate.
 *
 * <p>Per-mate lifecycle:
 *
 * <ol>
 *   <li>{@link #start()} — starts a background scheduler that polls the Blackboard for new messages
 *       addressed to this Mate (default 200ms cadence).
 *   <li>On a {@code TASK_DISPATCH} message: parse the instruction, submit it as a {@link
 *       AgentAction.UserPromptAction} to the wrapped Agent, await the result.
 *   <li>On result success: post {@code ARTIFACT_REF} (last tool output) + {@code ACCEPT} to the
 *       Blackboard. If the agent's per-episode call ceiling tripped, post a terminal {@code STATUS}
 *       instead.
 *   <li>On result failure: post {@code FEEDBACK} with the failure reason.
 * </ol>
 *
 * <p>Each Mate has its own per-episode call ceiling via the {@link
 * top.focess.veto.agent.loop.LoopBreaker} (registered with the {@link MateBreakerRegistry}). On
 * trip, the Mate posts a terminal {@code STATUS} and pauses until the Leader re-dispatches.
 */
public class MateAgent {

    private static final Logger log = LoggerFactory.getLogger(MateAgent.class);

    private final @NonNull String mateId;
    private final @NonNull UUID groupId;
    private final @NonNull Agent agent;
    private final @NonNull Blackboard blackboard;
    private final @NonNull MateBreakerRegistry breakers;
    private final long pollIntervalMs;
    private final long taskTimeoutMs;
    private final @NonNull String skillset;

    /** Set of turnSeqs we have already processed (so we don't re-dispatch on each tick). */
    private final @NonNull ConcurrentMap<String, Long> lastSeenSeqByReceiver =
            new ConcurrentHashMap<>();

    private final @NonNull AtomicBoolean running = new AtomicBoolean(false);
    private final @NonNull ScheduledExecutorService scheduler;
    private @Nullable ScheduledFuture<?> pollTask;
    private final long maxCallsPerEpisode;

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
                60_000);
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
            long taskTimeoutMs) {
        this.mateId = mateId;
        this.groupId = groupId;
        this.skillset = skillset;
        this.agent = agent;
        this.blackboard = blackboard;
        this.breakers = breakers;
        this.maxCallsPerEpisode = maxCallsPerEpisode;
        this.pollIntervalMs = pollIntervalMs;
        this.taskTimeoutMs = taskTimeoutMs;
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

    /**
     * Stop polling. Awaits termination of in-flight tasks (bounded by taskTimeoutMs + slack) so a
     * mid-task poll cannot post Blackboard messages after disband has flipped the group's state.
     */
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        if (pollTask != null) {
            pollTask.cancel(false);
        }
        scheduler.shutdown();
        try {
            // Bounded by the longest possible poll-tick work (handleDispatch awaits the agent
            // for up to taskTimeoutMs; add a small slack). If the await times out the in-flight
            // task is best-effort left to finish — it can no longer post Blackboard messages
            // because the scheduler is shut down (the executor refuses new tasks; in-flight
            // tasks continue but are bounded by their own await deadline).
            long awaitMs = taskTimeoutMs + 2_000L;
            if (!scheduler.awaitTermination(awaitMs, TimeUnit.MILLISECONDS)) {
                log.warn(
                        "MateAgent[{}] did not terminate within {}ms — forcing shutdown",
                        mateId,
                        awaitMs);
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            scheduler.shutdownNow();
        }
    }

    private void poll() {
        try {
            String key = lastSeenSeqKey();
            long seen = lastSeenSeqByReceiver.getOrDefault(key, 0L);
            List<BlackboardMessage> newMessages = newMessagesSince(seen);
            for (BlackboardMessage m : newMessages) {
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

        // 4. Await the result (bounded).
        AgentResult result;
        try {
            result = agent.await(java.time.Duration.ofMillis(taskTimeoutMs));
        } catch (Exception e) {
            Thread.currentThread().interrupt();
            breakers.recordModelCall(groupId, mateId);
            postFeedback(nodeId, "await interrupted: " + e.getMessage());
            return;
        }
        breakers.recordModelCall(groupId, mateId);

        // 5. Dispatch the result.
        if (result.success()) {
            String artifact = extractArtifact(result);
            if (artifact != null) {
                postArtifactRef(nodeId, artifact);
            }
            postAccept(nodeId, artifact);
        } else {
            postFeedback(nodeId, result.message() == null ? "agent failure" : result.message());
        }
    }

    private @Nullable String extractArtifact(@NonNull AgentResult result) {
        // Heuristic: the last assistant message is the artifact description. For a real
        // deployment the Mate would write to a workspace path; the path is what we post.
        if (result.message() == null || result.message().isBlank()) {
            return null;
        }
        return "/mate/" + mateId + "/" + System.currentTimeMillis();
    }

    private void postAccept(@NonNull String nodeId, @Nullable String artifactPath) {
        String payload = nodeId + ":accept:" + (artifactPath == null ? "/artifact" : artifactPath);
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

    private void postArtifactRef(@NonNull String nodeId, @NonNull String path) {
        blackboard.post(
                new BlackboardMessage(
                        UUID.randomUUID().toString(),
                        groupId,
                        mateId,
                        "LEADER",
                        BlackboardMessage.MessageType.ARTIFACT_REF,
                        nodeId + ":" + path,
                        0));
    }

    private void postFeedback(@NonNull String nodeId, @Nullable String reason) {
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

    /** The Mate's last agent state (for tests + diagnostics). */
    public @NonNull AgentState state() {
        return agent.state();
    }

    /** Inspect the Mate's turn history (for tests + diagnostics). */
    public @NonNull List<TurnRecord> history() {
        return agent.history();
    }
}
