package top.focess.veto.group;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import top.focess.veto.agent.Agent;
import top.focess.veto.agent.identity.AgentPersona;

/**
 * The Spring-orchestrated entry point for spawning a Group with auto-starting Mates. The {@link
 * GroupOrchestrator} drives the state machine; this service wraps the spawn path so calling code
 * (REST controllers, the {@code create_group} tool body) gets a fully-wired group with Mate agents
 * polling the Blackboard.
 *
 * <p>Per-mate wiring: each {@code create_mate} call produces a {@link MateAgent} bound to a fresh
 * {@link Agent} (the Mate's own ReAct loop). The Mate is started immediately and polls for
 * Blackboard messages addressed to its id. The spawner tracks every {@link MateAgent} it has
 * started so {@link #disband(UUID)} can stop them; previously, disband only flipped the group's
 * state in the registry and the Mate schedulers kept polling forever.
 */
@Service
public class GroupSpawner {

    private static final Logger log = LoggerFactory.getLogger(GroupSpawner.class);

    private final Blackboard blackboard;
    private final GroupRegistry registry;
    private final GroupOrchestrator orchestrator;
    private final MateBreakerRegistry breakers;
    private final long defaultMaxCallsPerEpisode;
    private final String mateModelId;

    /**
     * Live MateAgents per group. Used by {@link #disband(UUID)} to stop polling and shut down each
     * Mate's executor. Cleared on disband; a long-running process won't accumulate orphan
     * schedulers across many group lifecycles.
     */
    private final ConcurrentMap<UUID, List<MateAgent>> liveMates = new ConcurrentHashMap<>();

    /** A factory for creating fresh {@link Agent} instances (one per Mate). */
    @FunctionalInterface
    public interface AgentFactory {
        Agent create(AgentPersona persona);
    }

    public GroupSpawner(
            Blackboard blackboard,
            GroupRegistry registry,
            GroupOrchestrator orchestrator,
            MateBreakerRegistry breakers,
            @Value("${veto.group.mate.max_calls_per_episode:50}") long defaultMaxCallsPerEpisode,
            @Value("${veto.group.mate.model_id:#{null}}") String mateModelId) {
        this.blackboard = blackboard;
        this.registry = registry;
        this.orchestrator = orchestrator;
        this.breakers = breakers;
        this.defaultMaxCallsPerEpisode = defaultMaxCallsPerEpisode;
        // Default to an empty/null model id rather than a vendor-specific literal. The persona's
        // model binding flows through the Agent's LlmBinding — when this is null the Agent must
        // be configured with a binding externally (e.g. via veto.llm.* properties) before any
        // model call. The previous hardcoded "gemini-3.5-flash" broke non-Gemini deployments.
        this.mateModelId = mateModelId;
    }

    /**
     * Spawn a Group with the given Leader, contextBrief, DAG, and Mates. Each Mate gets its own
     * {@link Agent} (via the factory) + a {@link MateAgent} that auto-starts polling the
     * Blackboard. The returned Group has all the wiring; calling code can then drive orchestration
     * via {@link GroupOrchestrator#tick(java.util.UUID)}.
     */
    public Group spawn(
            String leaderId,
            String userId,
            String contextBrief,
            ExecutionDag dag,
            List<MateSpec> mateSpecs,
            AgentFactory agentFactory) {
        UUID groupId = UUID.randomUUID();
        // Group.create() already rebases the DAG onto the freshly-generated groupId; the
        // post-2014 manual rebase here was dead code.
        Group g = Group.create(leaderId, userId, contextBrief, blackboard, dag);
        registry.put(g);

        // Allocate and start each Mate, tracking the live instances for disband().
        List<MateAgent> started = new java.util.ArrayList<>();
        for (MateSpec spec : mateSpecs) {
            // Model id is now configurable (veto.group.mate.model_id). The previous hardcoded
            // "gemini-3.5-flash" broke non-Gemini deployments — the spawned Mate would try to
            // bind to a model the user has no credentials for and fail every tool call.
            String resolvedModelId = mateModelId;
            AgentPersona persona =
                    new AgentPersona(
                            spec.mateId,
                            spec.mateId,
                            "Mate " + spec.mateId + " (skillset: " + spec.skillset + ")",
                            java.util.Set.of(),
                            java.util.List.of(),
                            resolvedModelId,
                            null,
                            null);
            Agent agent = agentFactory.create(persona);
            MateAgent mate =
                    new MateAgent(
                            spec.mateId,
                            groupId,
                            spec.skillset,
                            agent,
                            blackboard,
                            breakers,
                            defaultMaxCallsPerEpisode);
            mate.start();
            started.add(mate);
            // Register the Mate on the group.
            g = g.withMate(spec.mateId, spec.skillset);
            log.info(
                    "GroupSpawner: spawned group {} with Mate {} (skillset={})",
                    groupId,
                    spec.mateId,
                    spec.skillset);
        }
        liveMates.put(groupId, started);
        registry.put(g);
        return g;
    }

    /** Convenience: spawn a single-Mate group (the common case for small tasks). */
    public Group spawn(
            String leaderId,
            String userId,
            String contextBrief,
            String singleMateId,
            String skillset,
            AgentFactory agentFactory) {

        // Use the same groupId for the linear DAG and the Group so we don't generate and discard
        // a UUID (the previous implementation did `ExecutionDag.linear(UUID.randomUUID(), ...)`
        // and let Group.create() immediately rebase — wasteful and confusing).
        UUID groupId = UUID.randomUUID();
        return spawn(
                leaderId,
                userId,
                contextBrief,
                ExecutionDag.linear(groupId, List.of("n1")),
                List.of(new MateSpec(singleMateId, skillset)),
                agentFactory);
    }

    /**
     * Spawn a Group with a DAG but no Mates yet — the Leader adds Mates via {@code create_mate} (or
     * the orchestrator assigns existing ones). This is the {@code create_group} path: it registers
     * the group + DAG so {@link GroupOrchestrator#tick} can drive it once Mates exist.
     */
    public Group spawnGroup(String leaderId, String userId, String contextBrief, ExecutionDag dag) {
        Group g = Group.create(leaderId, userId, contextBrief, blackboard, dag);
        registry.put(g);
        log.info("GroupSpawner: spawned group {} (no Mates yet)", g.groupId());
        return g;
    }

    /**
     * Add a Mate to an existing group: build the Mate's {@link Agent} via the factory, start its
     * polling {@link MateAgent}, and register it on the group. The Leader uses {@code create_mate}
     * to add a Mate under a skillset tag.
     */
    public void addMate(UUID groupId, String mateId, String skillset, AgentFactory agentFactory) {
        Group g = registry.get(groupId);
        if (g == null) {
            throw new IllegalArgumentException("unknown group: " + groupId);
        }
        AgentPersona persona =
                new AgentPersona(
                        mateId,
                        mateId,
                        "Mate " + mateId + " (skillset: " + skillset + ")",
                        java.util.Set.of(),
                        java.util.List.of(),
                        mateModelId,
                        null,
                        null);
        Agent agent = agentFactory.create(persona);
        MateAgent mate =
                new MateAgent(
                        mateId,
                        groupId,
                        skillset,
                        agent,
                        blackboard,
                        breakers,
                        defaultMaxCallsPerEpisode);
        mate.start();
        liveMates
                .computeIfAbsent(groupId, k -> new java.util.concurrent.CopyOnWriteArrayList<>())
                .add(mate);
        registry.put(g.withMate(mateId, skillset));
        log.info(
                "GroupSpawner: added Mate {} (skillset={}) to group {}", mateId, skillset, groupId);
    }

    /**
     * Remove a Mate from a group: stop its polling scheduler and drop it from the group + the live
     * tracker. In-flight nodes the Mate owned go back to PENDING on the next tick for
     * re-assignment.
     */
    public void removeMate(UUID groupId, String mateId) {
        List<MateAgent> mates = liveMates.get(groupId);
        if (mates != null) {
            for (MateAgent m : mates) {
                if (m.mateId().equals(mateId)) {
                    m.stop();
                }
            }
            mates.removeIf(m -> m.mateId().equals(mateId));
        }
        Group g = registry.get(groupId);
        if (g != null) {
            registry.put(g.withoutMate(mateId));
        }
        log.info("GroupSpawner: removed Mate {} from group {}", mateId, groupId);
    }

    /**
     * Disband a group: stop every live MateAgent (cancel poll + shut down the executor), drop the
     * breaker registry entries, mark the group DISBANDED, and retain the Blackboard for audit.
     * Idempotent — calling disband twice is a no-op.
     */
    public void disband(java.util.UUID groupId) {
        Group g = registry.get(groupId);
        if (g == null) {
            // Even if the group record is gone, still try to clean up any straggler Mates.
            stopMates(groupId);
            if (orchestrator != null) {
                orchestrator.onGroupDisbanded(groupId);
            }
            return;
        }
        // Stop Mates first so they stop writing to the Blackboard; then flip state.
        stopMates(groupId);
        breakers.clear(groupId);
        registry.disband(groupId, java.time.Instant.now());
        if (orchestrator != null) {
            orchestrator.onGroupDisbanded(groupId);
        }
    }

    private void stopMates(UUID groupId) {
        List<MateAgent> mates = liveMates.remove(groupId);
        if (mates == null) {
            return;
        }
        for (MateAgent m : mates) {
            try {
                m.stop();
            } catch (Exception e) {
                log.warn(
                        "GroupSpawner: failed to stop Mate {} in group {}", m.mateId(), groupId, e);
            }
        }
    }

    /** A specification for a Mate in a group. */
    public record MateSpec(String mateId, String skillset) {
        public MateSpec {
            if (mateId == null || mateId.isBlank()) {
                throw new IllegalArgumentException("mateId");
            }
            if (skillset == null || skillset.isBlank()) {
                throw new IllegalArgumentException("skillset");
            }
        }
    }
}
