package top.focess.veto.group;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import top.focess.veto.agent.Agent;
import top.focess.veto.agent.identity.AgentPersona;
import top.focess.veto.agent.identity.Role;
import top.focess.veto.model.tier.ModelTier;

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
 *
 * <p>Model tier per Mate: a Mate runs on the tier chosen for its skillset. {@link
 * #resolveMateBinding} looks up {@link SkillsetProperties} for the skillset and falls back to the
 * global {@code veto.group.mate.tier} (default {@code MID}) + {@code
 * veto.group.mate.system-prompt-base}. The concrete provider / model / credential are resolved from
 * the active model-tier profile by the factory, so switching profiles swaps every Mate's model at
 * once. The pattern / agent never name a concrete model - only the tier.
 */
@Service
public class GroupSpawner implements GroupOrchestrator.MateProvisioner {

    private static final Logger log = LoggerFactory.getLogger(GroupSpawner.class);

    private static final String DEFAULT_MATE_SYSTEM_PROMPT_BASE =
            "You are a Mate agent. Execute the assigned task.";

    private final @NonNull Blackboard blackboard;
    private final @NonNull GroupRegistry registry;
    private final @NonNull GroupOrchestrator orchestrator;
    private final @NonNull MateBreakerRegistry breakers;
    private final @NonNull SkillsetProperties skillsetProperties;
    private final long defaultMaxCallsPerEpisode;
    private final @NonNull ModelTier mateTier;
    private final @NonNull String mateSystemPromptBase;
    private final @Nullable AgentFactory agentFactory;

    /**
     * Live MateAgents per group. Used by {@link #disband(UUID)} to stop polling and shut down each
     * Mate's executor. Cleared on disband; a long-running process won't accumulate orphan
     * schedulers across many group lifecycles.
     */
    private final @NonNull ConcurrentMap<UUID, List<MateAgent>> liveMates =
            new ConcurrentHashMap<>();

    /** A factory for creating fresh {@link Agent} instances (one per Mate). */
    @FunctionalInterface
    public interface AgentFactory {
        @NonNull Agent create(@NonNull AgentPersona persona, @NonNull MateBinding mateBinding);
    }

    @Autowired
    public GroupSpawner(
            @NonNull Blackboard blackboard,
            @NonNull GroupRegistry registry,
            @NonNull GroupOrchestrator orchestrator,
            @NonNull MateBreakerRegistry breakers,
            @NonNull SkillsetProperties skillsetProperties,
            @Value("${veto.group.mate.max_calls_per_episode:50}") long defaultMaxCallsPerEpisode,
            @Value("${veto.group.mate.tier:MID}") @Nullable String mateTier,
            @Value("${veto.group.mate.system-prompt-base:" + DEFAULT_MATE_SYSTEM_PROMPT_BASE + "}")
                    @NonNull String mateSystemPromptBase,
            @Nullable AgentFactory agentFactory) {
        this.blackboard = blackboard;
        this.registry = registry;
        this.orchestrator = orchestrator;
        this.breakers = breakers;
        this.skillsetProperties = skillsetProperties;
        this.defaultMaxCallsPerEpisode = defaultMaxCallsPerEpisode;
        this.mateTier = parseTier(mateTier);
        this.mateSystemPromptBase = mateSystemPromptBase;
        // Injected so GroupSpawner can both spawn whole groups (caller supplies a factory) and
        // provision individual Mates on demand as the orchestrator dispatches DAG nodes. May be
        // null in tests that only exercise the spawn/register paths; provision() then refuses.
        this.agentFactory = agentFactory;
    }

    /** Test-only constructor: no agent factory, so {@link #provision} is unavailable. */
    public GroupSpawner(
            @NonNull Blackboard blackboard,
            @NonNull GroupRegistry registry,
            @NonNull GroupOrchestrator orchestrator,
            @NonNull MateBreakerRegistry breakers,
            long defaultMaxCallsPerEpisode) {
        this(
                blackboard,
                registry,
                orchestrator,
                breakers,
                new SkillsetProperties(),
                defaultMaxCallsPerEpisode,
                "MID",
                DEFAULT_MATE_SYSTEM_PROMPT_BASE,
                null);
    }

    /**
     * Resolve a Mate's tier + system-prompt base for a skillset. A skillset entry in {@link
     * SkillsetProperties} overrides either field individually; unset fields fall back to the global
     * {@code veto.group.mate.tier} / {@code veto.group.mate.system-prompt-base}.
     */
    private @NonNull MateBinding resolveMateBinding(
            @NonNull String skillset, @Nullable Group group) {
        SkillsetProperties.SkillsetConfig cfg = skillsetProperties.forSkillset(skillset);
        ModelTier tier = (cfg != null && cfg.getTier() != null) ? cfg.getTier() : mateTier;
        String base =
                (cfg != null && cfg.getSystemPromptBase() != null)
                        ? cfg.getSystemPromptBase()
                        : mateSystemPromptBase;
        return new MateBinding(tier, base, group != null ? group.owner() : null);
    }

    /**
     * Spawn a Group with the given Leader, contextBrief, DAG, and Mates. Each Mate gets its own
     * {@link Agent} (via the factory) + a {@link MateAgent} that auto-starts polling the
     * Blackboard. The returned Group has all the wiring; calling code can then drive orchestration
     * via {@link GroupOrchestrator#tick(java.util.UUID)}.
     */
    public @NonNull Group spawn(
            @NonNull String leaderId,
            @NonNull String userId,
            @Nullable String contextBrief,
            @NonNull ExecutionDag dag,
            @NonNull List<MateSpec> mateSpecs,
            @NonNull AgentFactory agentFactory) {
        UUID groupId = UUID.randomUUID();
        // Group.create() already rebases the DAG onto the freshly-generated groupId; the
        // post-2014 manual rebase here was dead code.
        Group g = Group.create(leaderId, userId, contextBrief, blackboard, dag);
        registry.put(g);

        // Allocate and start each Mate, tracking the live instances for disband().
        List<MateAgent> started = new java.util.ArrayList<>();
        for (MateSpec spec : mateSpecs) {
            MateAgent mate = startMate(groupId, spec.mateId(), spec.skillset(), agentFactory);
            started.add(mate);
            // Register the Mate on the group.
            g = g.withMate(spec.mateId(), spec.skillset());
            log.info(
                    "GroupSpawner: spawned group {} with Mate {} (skillset={})",
                    groupId,
                    spec.mateId(),
                    spec.skillset());
        }
        liveMates.put(groupId, started);
        registry.put(g);
        return g;
    }

    /** Convenience: spawn a single-Mate group (the common case for small tasks). */
    public @NonNull Group spawn(
            @NonNull String leaderId,
            @NonNull String userId,
            @Nullable String contextBrief,
            @NonNull String singleMateId,
            @NonNull String skillset,
            @NonNull AgentFactory agentFactory) {

        // Use the same groupId for the linear DAG and the Group so we don't generate and discard
        // a UUID (the previous implementation did `ExecutionDag.linear(UUID.randomUUID(), ...)`
        // and let Group.create() immediately rebase - wasteful and confusing).
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
     * Spawn a Group with a DAG but no Mates yet - the Leader adds Mates via {@code create_mate} (or
     * the orchestrator assigns existing ones). This is the {@code create_group} path: it registers
     * the group + DAG so {@link GroupOrchestrator#tick} can drive it once Mates exist.
     */
    public @NonNull Group spawnGroup(
            @NonNull String leaderId,
            @NonNull String userId,
            @NonNull String contextBrief,
            @NonNull ExecutionDag dag) {
        Group g = Group.create(leaderId, userId, contextBrief, blackboard, dag);
        registry.put(g);
        log.info("GroupSpawner: spawned group {} (no Mates yet)", g.groupId());
        return g;
    }

    /**
     * Register an empty group - no DAG, no Mates. This is the Model B {@code create_group} path:
     * the calling agent transforms into the Leader and authors the execution DAG node by node via
     * {@code create_node}; the orchestrator provisions Mates lazily on dispatch. Returns the
     * registered group so the caller can stamp its id into the transform directive.
     */
    public @NonNull Group registerEmptyGroup(
            @NonNull String leaderId,
            @NonNull String userId,
            @Nullable String owner,
            @NonNull String contextBrief) {
        Group g =
                Group.create(
                        leaderId,
                        userId,
                        contextBrief,
                        blackboard,
                        new ExecutionDag(UUID.randomUUID(), java.util.List.of()),
                        owner);
        registry.put(g);
        log.info(
                "GroupSpawner: registered empty group {} (Leader will author the DAG)",
                g.groupId());
        return g;
    }

    /**
     * Add a Mate to an existing group: build the Mate's {@link Agent} via the factory, start its
     * polling {@link MateAgent}, and register it on the group. The Leader uses {@code create_mate}
     * to add a Mate under a skillset tag.
     */
    public void addMate(
            @NonNull UUID groupId,
            @NonNull String mateId,
            @NonNull String skillset,
            @NonNull AgentFactory agentFactory) {
        Group g = registry.get(groupId);
        if (g == null) {
            throw new IllegalArgumentException("unknown group: " + groupId);
        }
        startMate(groupId, mateId, skillset, agentFactory);
        registry.put(g.withMate(mateId, skillset));
        log.info(
                "GroupSpawner: added Mate {} (skillset={}) to group {}", mateId, skillset, groupId);
    }

    /**
     * Build and start a single Mate's {@link Agent} + polling {@link MateAgent} and track it for
     * {@link #disband(UUID)}. Does NOT touch the registry: the caller owns the atomic group update
     * (the orchestrator stamps the mate onto the group together with the node assignment so a
     * concurrent tick never observes a half-wired group). Shared by {@link #addMate} (which then
     * writes the registry) and {@link #provision} (which leaves the registry to the orchestrator).
     */
    private @NonNull MateAgent startMate(
            @NonNull UUID groupId,
            @NonNull String mateId,
            @NonNull String skillset,
            @NonNull AgentFactory agentFactory) {
        Group group = registry.get(groupId);
        MateBinding mateBinding = resolveMateBinding(skillset, group);
        AgentPersona persona =
                new AgentPersona(
                        mateId,
                        mateId,
                        "Mate " + mateId + " (skillset: " + skillset + ")",
                        java.util.Set.of(),
                        java.util.List.of(),
                        Role.MATE);
        Agent agent = agentFactory.create(persona, mateBinding);
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
        return mate;
    }

    /**
     * {@link GroupOrchestrator.MateProvisioner} entry point: lazily provision a Mate for a
     * dispatchable DAG node that no existing Mate can serve. Generates a fresh mate id, starts the
     * Mate, and returns its id. The orchestrator performs the registry write (stamping the Mate
     * onto the group alongside the node assignment) so the group update stays atomic.
     */
    @Override
    public @NonNull String provision(@NonNull UUID groupId, @NonNull String skillset) {
        AgentFactory factory = agentFactory;
        if (factory == null) {
            throw new IllegalStateException(
                    "GroupSpawner.provision called without an AgentFactory (test wiring only)");
        }
        String mateId = UUID.randomUUID().toString();
        startMate(groupId, mateId, skillset, factory);
        log.info(
                "GroupSpawner: provisioned Mate {} (skillset={}) for group {} on demand",
                mateId,
                skillset,
                groupId);
        return mateId;
    }

    /**
     * Remove a Mate from a group: stop its polling scheduler and drop it from the group + the live
     * tracker. In-flight nodes the Mate owned go back to PENDING on the next tick for
     * re-assignment.
     */
    public void removeMate(@NonNull UUID groupId, @NonNull String mateId) {
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
     * Idempotent - calling disband twice is a no-op.
     */
    public void disband(java.util.@NonNull UUID groupId) {
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

    private void stopMates(@NonNull UUID groupId) {
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

    private static @NonNull ModelTier parseTier(@Nullable String s) {
        if (s == null || s.isBlank()) {
            return ModelTier.MID;
        }
        try {
            return ModelTier.valueOf(s);
        } catch (IllegalArgumentException e) {
            return ModelTier.MID;
        }
    }

    /** A specification for a Mate in a group. */
    public record MateSpec(@NonNull String mateId, @NonNull String skillset) {
        public MateSpec {
            if (mateId.isBlank()) {
                throw new IllegalArgumentException("mateId");
            }
            if (skillset.isBlank()) {
                throw new IllegalArgumentException("skillset");
            }
        }
    }
}
