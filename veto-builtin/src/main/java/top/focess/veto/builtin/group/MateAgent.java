package top.focess.veto.builtin.group;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.focess.veto.api.agent.AgentResult;
import top.focess.veto.api.agent.AgentState;
import top.focess.veto.api.plugin.agent.AgentHost;

/** Plugin-owned dispatch waiter; host request handles own execution and cancellation. */
public class MateAgent {

    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.builtin.group.MateAgent");

    private final @NonNull String mateId;
    private final @NonNull UUID groupId;
    private final AgentHost.@NonNull Child agent;
    private final @NonNull Blackboard blackboard;
    private final long pollIntervalMs;
    private final long resultPollIntervalMs;
    private final @NonNull String skillset;

    /** Set of turnSeqs we have already processed (so we don't re-dispatch on each tick). */
    private final @NonNull ConcurrentMap<String, Long> lastSeenSeqByReceiver =
            new ConcurrentHashMap<>();

    private final @NonNull AtomicBoolean running = new AtomicBoolean(false);
    private boolean closeRequested;
    private final @NonNull ScheduledExecutorService scheduler;
    private ScheduledFuture<?> pollTask;
    private final @NonNull Set<String> cancelledDispatches = ConcurrentHashMap.newKeySet();
    private String activeDispatch;
    private AgentHost.Request activeRequest;
    private @NonNull CompletableFuture<Boolean> dispatchExited =
            CompletableFuture.completedFuture(true);

    public boolean cancelDispatch(@NonNull String dispatchId, @NonNull Duration timeout)
            throws InterruptedException {
        AgentHost.Request task;
        CompletableFuture<Boolean> exited;
        synchronized (this) {
            cancelledDispatches.add(dispatchId);
            if (!dispatchId.equals(activeDispatch)) return true;
            task = activeRequest;
            exited = dispatchExited;
        }
        if (task == null) return false;
        long started = System.nanoTime();
        if (!task.cancel(timeout)) return false;
        long remaining = Math.max(0, timeout.toNanos() - (System.nanoTime() - started));
        try {
            return exited.get(remaining, TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            return false;
        } catch (ExecutionException e) {
            throw new IllegalStateException(e.getCause());
        }
    }

    public MateAgent(
            @NonNull String mateId,
            @NonNull UUID groupId,
            @NonNull String skillset,
            AgentHost.@NonNull Child agent,
            @NonNull Blackboard blackboard) {
        this(mateId, groupId, skillset, agent, blackboard, 200, 1_000);
    }

    public MateAgent(
            @NonNull String mateId,
            @NonNull UUID groupId,
            @NonNull String skillset,
            AgentHost.@NonNull Child agent,
            @NonNull Blackboard blackboard,
            long pollIntervalMs,
            long resultPollIntervalMs) {
        this.mateId = mateId;
        this.groupId = groupId;
        this.skillset = skillset;
        this.agent = agent;
        this.blackboard = blackboard;
        this.pollIntervalMs = pollIntervalMs;
        this.resultPollIntervalMs = resultPollIntervalMs;
        this.scheduler =
                Executors.newSingleThreadScheduledExecutor(
                        r -> {
                            Thread t = new Thread(r, "mate-" + mateId);
                            t.setDaemon(true);
                            return t;
                        });
    }

    public @NonNull String mateId() {
        return mateId;
    }

    public @NonNull String skillset() {
        return skillset;
    }

    public AgentHost.@NonNull Child agent() {
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
    public synchronized void stop() {
        running.set(false);
        try {
            if (!closeRequested) {
                agent.close();
                closeRequested = true;
            }
        } finally {
            if (pollTask != null) pollTask.cancel(true);
            scheduler.shutdownNow();
        }
    }

    /** Confirm both the dispatch waiter and underlying execution have exited. */
    public boolean awaitTermination(@NonNull Duration timeout) throws InterruptedException {
        if (timeout.isNegative()) throw new IllegalArgumentException("Negative timeout");
        long started = System.nanoTime();
        long nanos = timeout.toNanos();
        if (!scheduler.awaitTermination(nanos, TimeUnit.NANOSECONDS)) return false;
        long remaining = Math.max(0, nanos - (System.nanoTime() - started));
        return agent.awaitTermination(Duration.ofNanos(remaining));
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
            postFeedback(dispatch.payload(), "malformed dispatch payload", dispatch.dispatchId());
            return;
        }
        String nodeId = payload.substring(0, colon).strip();
        String instruction = payload.substring(colon + 1).strip();

        log.info("MateAgent[{}] dispatching node {}: {}", mateId, nodeId, instruction);
        String dispatchId = dispatch.dispatchId();
        AgentHost.Request request;
        synchronized (this) {
            if (dispatchId != null && cancelledDispatches.contains(dispatchId)) return;
            request = agent.submit(instruction);
            activeDispatch = dispatchId;
            activeRequest = request;
            dispatchExited = new CompletableFuture<>();
        }
        try {
            // Await the actual task result. A polling deadline is not a task failure: a Mate
            // can legitimately need several model calls, tool waits, or user approvals.
            while (running.get()) {
                try {
                    AgentResult result =
                            request.result().get(resultPollIntervalMs, TimeUnit.MILLISECONDS);
                    if (!running.get()
                            || (dispatchId != null && cancelledDispatches.contains(dispatchId))) {
                        return;
                    }
                    if (result.success()) {
                        postAccept(nodeId, result.message(), dispatch.dispatchId());
                    } else {
                        postFeedback(nodeId, result.message(), dispatch.dispatchId());
                    }
                    return;
                } catch (TimeoutException e) {
                    // Check lifecycle state, then continue waiting for this same task.
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    if (running.get()) {
                        postFeedback(nodeId, "result wait interrupted", dispatch.dispatchId());
                    }
                    return;
                } catch (RuntimeException | ExecutionException e) {
                    if (running.get()) {
                        postFeedback(
                                nodeId,
                                "result wait failed: " + e.getMessage(),
                                dispatch.dispatchId());
                    }
                    return;
                }
            }
        } finally {
            synchronized (this) {
                dispatchExited.complete(true);
            }
        }
    }

    private void postAccept(@NonNull String nodeId, @NonNull String summary, String dispatchId) {
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
                        0,
                        dispatchId));
    }

    private void postFeedback(@NonNull String nodeId, String reason, String dispatchId) {
        blackboard.post(
                new BlackboardMessage(
                        UUID.randomUUID().toString(),
                        groupId,
                        mateId,
                        "LEADER",
                        BlackboardMessage.MessageType.FEEDBACK,
                        nodeId + ":feedback:" + (reason == null ? "unknown" : reason),
                        0,
                        dispatchId));
    }

    private void postTerminalStatus(
            @NonNull String nodeId, @NonNull String reason, String dispatchId) {
        blackboard.post(
                new BlackboardMessage(
                        UUID.randomUUID().toString(),
                        groupId,
                        mateId,
                        "LEADER",
                        BlackboardMessage.MessageType.STATUS,
                        "terminal:" + nodeId + ":" + reason,
                        0,
                        dispatchId));
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
}
