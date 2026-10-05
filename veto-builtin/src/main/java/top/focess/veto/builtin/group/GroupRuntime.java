package top.focess.veto.builtin.group;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.event.AgentTerminatedEvent;
import top.focess.veto.api.event.EventHandler;
import top.focess.veto.api.event.Listener;
import top.focess.veto.api.event.SessionDeletedEvent;
import top.focess.veto.api.event.UserLogoutEvent;
import top.focess.veto.api.llm.PromptRenderer;
import top.focess.veto.api.llm.ToolResultPresentationMode;
import top.focess.veto.api.plugin.PluginContext;
import top.focess.veto.api.plugin.PluginHost;
import top.focess.veto.api.plugin.Scope;
import top.focess.veto.api.plugin.agent.AgentHost;
import top.focess.veto.api.plugin.agent.AgentProfile;
import top.focess.veto.api.plugin.contract.AgentConfiguration;
import top.focess.veto.api.plugin.contract.JsonValue;
import top.focess.veto.api.plugin.storage.PluginStorage;

/**
 * Owns the complete group feature, including activation, policy, persistence and shutdown.
 *
 * <p>Host configuration and tool callbacks may overlap the group scheduler. The runtime monitor
 * serializes configuration, group creation and scheduler start/close; one concurrent map publishes
 * immutable per-agent context, transition and recovery state under the full identity. DAG edits and
 * shutdown use the orchestrator's per-group locks. Closing stops scheduling before draining each
 * group's members, and may wait under the runtime monitor. Host lifecycle admission must prevent
 * new calls once shutdown begins.
 */
public final class GroupRuntime implements AgentConfiguration, GroupObservations, AutoCloseable {
    private final @NonNull Listener listener = new GroupListener();

    /** Event aspect for this group runtime. */
    public @NonNull Listener listener() {
        return listener;
    }

    private final class GroupListener implements Listener {
        @EventHandler
        public void onSessionDeleted(@NonNull SessionDeletedEvent event) {
            GroupRuntime.this.onSessionDeleted(event);
        }

        @EventHandler
        public void onAgentTerminated(@NonNull AgentTerminatedEvent event) {
            GroupRuntime.this.onAgentTerminated(event);
        }

        @EventHandler
        public void onUserLogout(@NonNull UserLogoutEvent event) {
            GroupRuntime.this.onUserLogout(event);
        }
    }

    private final @NonNull GroupConfig configuration;
    private final PluginHost host;
    private final PromptRenderer prompts;
    private final GroupHistoryStore history;
    private final @NonNull GroupRegistry groups = new GroupRegistry();
    private final @NonNull Blackboard board = new Blackboard();
    private final @NonNull GroupSpawner spawner;
    private final @NonNull GroupOrchestrator orchestrator;
    private final @NonNull GroupOperations operations;

    private record AgentState(Context context, Transition transition, boolean restored) {}

    private final @NonNull Map<Scope.@NonNull AgentScope, @NonNull AgentState> agents =
            new ConcurrentHashMap<>();
    private ScheduledExecutorService scheduler;
    private volatile boolean activationReady = true;

    /** Defers configuration until {@link #start} signals that host startup completed. */
    public void awaitHostReady() {
        activationReady = false;
    }

    /** Creates the runtime from the plugin context with empty configuration. */
    public GroupRuntime(@NonNull PluginContext context) {
        this(context, new JsonValue.ObjectValue(Map.of()));
    }

    /** Creates the runtime, resolving host services and wiring spawner and orchestrator. */
    public GroupRuntime(@NonNull PluginContext context, JsonValue.@NonNull ObjectValue config) {
        configuration = new GroupConfig(config.values());
        configuration.tickMillis();
        host = context.service(PluginHost.class).orElse(null);
        prompts = context.service(PromptRenderer.class).orElse(null);
        var storage = context.service(PluginStorage.class).orElse(null);
        history = storage == null ? null : new GroupHistoryStore(storage);
        if (history != null) groups.attachHistory(history);
        if (host != null) groups.attachInvalidations(host);
        AtomicReference<GroupRuntime> readyRuntime = new AtomicReference<>();
        spawner =
                new GroupSpawner(
                        groups,
                        board,
                        new GroupSpawner.AgentFactory() {
                            public AgentHost.@NonNull Child open(
                                    @NonNull Group group,
                                    @NonNull String id,
                                    @NonNull String name,
                                    @NonNull String responsibility) {
                                return Objects.requireNonNull(readyRuntime.get())
                                        .openMate(group, id, name, responsibility, null);
                            }

                            public AgentHost.@NonNull Child openSkilled(
                                    @NonNull Group group,
                                    @NonNull String id,
                                    @NonNull String skillset) {
                                return Objects.requireNonNull(readyRuntime.get())
                                        .openMate(group, id, skillset, skillset, skillset);
                            }
                        });
        orchestrator = new GroupOrchestrator(groups, board, new HeuristicLeader(), spawner);
        operations =
                new GroupOperations(
                        () -> Objects.requireNonNull(readyRuntime.get()),
                        spawner,
                        groups,
                        board,
                        orchestrator);
        readyRuntime.set(this);
    }

    /** Capability behind the Leader's group-control tools. */
    public @NonNull GroupControlCapability operations() {
        return operations;
    }

    /** Capability behind the {@code create_group} delegation tool. */
    public @NonNull DelegationCapability delegation() {
        return operations;
    }

    /** Group history store; fails when plugin storage is unavailable. */
    public @NonNull GroupHistoryStore history() {
        if (history == null) throw new IllegalStateException("Group storage unavailable");
        return history;
    }

    /** Registry of the groups currently owned by this runtime. */
    public @NonNull GroupRegistry registry() {
        return groups;
    }

    private static Scope.@NonNull AgentScope key(@NonNull Context context) {
        return new Scope.AgentScope(
                context.owner(), context.storageGrant().scope().session(), context.agentId());
    }

    private void transition(Scope.@NonNull AgentScope scope, @NonNull Transition transition) {
        agents.compute(
                scope,
                (ignored, prior) ->
                        new AgentState(
                                prior == null ? null : prior.context(),
                                transition,
                                prior != null && prior.restored()));
    }

    // WHY: compute's external return remains nullable to Checker despite the non-null remapping.
    @SuppressWarnings("ConstantValue")
    public synchronized Intent configure(@NonNull Context context) {
        if (!activationReady) throw new IllegalStateException("Host startup is not ready");
        var key = key(context);
        var agent =
                agents.compute(
                        key,
                        (ignored, prior) ->
                                new AgentState(
                                        context,
                                        prior == null ? null : prior.transition(),
                                        prior != null && prior.restored()));
        if (history == null) return null;
        history.grant(context.storageGrant());
        if (context.authorizedTools().stream()
                        .noneMatch(
                                tool ->
                                        GroupProfiles.owns(tool, Set.of("create_group"))
                                                && context.base().tools().contains(tool.name()))
                && history.profile(context.storageGrant().scope().session(), context.agentId())
                        == null) return null;
        // Children keep their feature-owned profile; do not accidentally acquire standalone tools.
        var own = history.profile(context.storageGrant().scope().session(), context.agentId());
        if (own != null && own.label().equals("MATE"))
            return new Intent(own, transitionFor(context));
        Group group =
                groups.snapshot().values().stream()
                        .filter(
                                value ->
                                        context.agentId().equals(value.leaderId())
                                                && context.storageGrant()
                                                        .scope()
                                                        .session()
                                                        .equals(String.valueOf(value.sessionId()))
                                                && value.state() != GroupState.DISBANDED)
                        .findFirst()
                        .orElse(null);
        if (group == null && agent != null && !agent.restored()) {
            var saved =
                    history.latestSnapshots(context.storageGrant().scope().session()).stream()
                            .filter(
                                    value ->
                                            value.leaderId().equals(context.agentId())
                                                    && !value.historical())
                            .max(Comparator.comparing(GroupHistoryView::createdAt))
                            .orElse(null);
            if (saved != null && !saved.state().equals("DISBANDED")) {
                var roster = saved.mates();
                if (roster == null)
                    throw new IllegalStateException(
                            "Team snapshot has no complete member roster; recovery is unavailable."
                                    + " History is retained.");
                UUID id = UUID.fromString(saved.id());
                group =
                        new Group(
                                id,
                                context.agentId(),
                                context.storageGrant().scope().owner(),
                                saved.brief(),
                                new ExecutionDag(
                                        id,
                                        saved.nodes().stream()
                                                .map(GroupRuntime::restoreNode)
                                                .toList()),
                                board,
                                roster,
                                GroupState.RECOVERING,
                                saved.createdAt(),
                                null,
                                context.owner(),
                                context.agents(),
                                ToolResultPresentationMode.BASIC,
                                UUID.fromString(context.storageGrant().scope().session()));
                groups.put(group);
            }
        }
        if (group != null && group.state() == GroupState.RECOVERING) {
            spawner.restoreMates(group);
            group = group.withState(GroupState.ACTIVE, Instant.now());
            groups.put(group);
        }
        agents.computeIfPresent(
                key, (ignored, prior) -> new AgentState(prior.context(), prior.transition(), true));
        AgentProfile profile = own == null ? GroupProfiles.standalone(context) : own;
        if (group != null) {
            String profileKey = group.groupId() + "/leader";
            var savedProfile =
                    history.profile(context.storageGrant().scope().session(), profileKey);
            if (savedProfile == null) {
                savedProfile =
                        GroupProfiles.role(
                                context,
                                context.base().name(),
                                context.base().description(),
                                true,
                                configuration,
                                null);
                history.profile(context.storageGrant().scope().session(), profileKey, savedProfile);
            }
            profile = savedProfile;
        }
        return new Intent(profile, transitionFor(context));
    }

    private Transition transitionFor(@NonNull Context context) {
        var state = agents.get(key(context));
        var pending = state == null ? null : state.transition();
        if (pending == null || !pending.prompt().equals("runtime-leader")) return pending;
        var data = new LinkedHashMap<>(pending.data().values());
        data.put("task", new JsonValue.StringValue(context.activeTask()));
        return new Transition(pending.key(), pending.prompt(), new JsonValue.ObjectValue(data));
    }

    private AgentHost.@NonNull Child openMate(
            @NonNull Group group,
            @NonNull String id,
            @NonNull String name,
            @NonNull String responsibility,
            String skillset) {
        var session = group.sessionId();
        if (session == null) throw new IllegalStateException("Missing session");
        var owner = group.owner();
        if (owner == null) throw new IllegalStateException("Missing group owner");
        var state = agents.get(new Scope.AgentScope(owner, session.toString(), group.leaderId()));
        var context = state == null ? null : state.context();
        if (context == null) throw new IllegalStateException("Team leader is not active");
        var profile = history().profile(session.toString(), id);
        if (profile == null) {
            profile =
                    GroupProfiles.role(
                            context, name, responsibility, false, configuration, skillset);
            history().profile(session.toString(), id, profile);
        }
        if (group.state() == GroupState.RECOVERING) {
            var attempts =
                    group.dag().nodes().stream()
                            .filter(
                                    node ->
                                            id.equals(node.assignedMateId())
                                                    && node.state()
                                                            == DagNode.NodeState.INTERRUPTED)
                            .<JsonValue>map(
                                    node -> {
                                        Map<String, JsonValue> data = new LinkedHashMap<>();
                                        data.put(
                                                "groupId",
                                                new JsonValue.StringValue(
                                                        group.groupId().toString()));
                                        data.put(
                                                "nodeId", new JsonValue.StringValue(node.nodeId()));
                                        var dispatchId = node.dispatchId();
                                        var requestId = node.requestId();
                                        if (dispatchId != null)
                                            data.put(
                                                    "dispatchId",
                                                    new JsonValue.StringValue(dispatchId));
                                        if (requestId != null)
                                            data.put(
                                                    "requestId",
                                                    new JsonValue.StringValue(requestId));
                                        return new JsonValue.ObjectValue(data);
                                    })
                            .toList();
            Map<String, JsonValue> bindings = new LinkedHashMap<>();
            var priorPrompt = profile.prompt();
            if (priorPrompt != null) bindings.putAll(priorPrompt.data().values());
            bindings.put("tasks", new JsonValue.ArrayValue(attempts));
            profile =
                    new AgentProfile(
                            profile.name(),
                            profile.description(),
                            profile.label(),
                            profile.tools(),
                            profile.tier(),
                            new AgentProfile.Prompt(
                                    "builtin-mate-profile", new JsonValue.ObjectValue(bindings)),
                            profile.metadata());
            history().profile(session.toString(), id, profile);
            transition(
                    new Scope.AgentScope(owner, session.toString(), id),
                    new Transition(
                            "recovery:" + group.groupId() + ":" + id,
                            "runtime-group-recovery",
                            new JsonValue.ObjectValue(
                                    Map.of("tasks", new JsonValue.ArrayValue(attempts)))));
        }
        return context.agents().open(id, group.leaderId(), profile);
    }

    PluginHost.@NonNull Invocation invocation(@NonNull String tool) {
        if (host == null) throw new IllegalStateException("Plugin host unavailable");
        return host.invocation(tool);
    }

    @NonNull String prompt(@NonNull String source, @NonNull Map<String, Object> data) {
        if (prompts == null) throw new IllegalStateException("Prompt compiler unavailable");
        return prompts.compile(source, data);
    }

    synchronized void create(PluginHost.@NonNull Invocation scope, @NonNull String brief) {
        var state = agents.get(scope.scope());
        var context = state == null ? null : state.context();
        if (context == null) throw new IllegalStateException("Agent configuration is not active");
        if (groups.snapshot().values().stream()
                .anyMatch(
                        group ->
                                group.leaderId().equals(scope.agentId())
                                        && group.state() != GroupState.DISBANDED))
            throw new IllegalStateException("Agent already owns a group");
        history().profile(scope.sessionId(), scope.agentId(), GroupProfiles.standalone(context));
        UUID id = UUID.randomUUID();
        var group =
                new Group(
                        id,
                        scope.agentId(),
                        context.storageGrant().scope().owner(),
                        brief,
                        new ExecutionDag(id, List.of()),
                        board,
                        Map.of(),
                        GroupState.ACTIVE,
                        Instant.now(),
                        null,
                        scope.owner(),
                        context.agents(),
                        ToolResultPresentationMode.BASIC,
                        UUID.fromString(scope.sessionId()));
        groups.put(group);
        transition(scope, "runtime-leader", brief);
    }

    void transition(
            PluginHost.@NonNull Invocation scope, @NonNull String prompt, @NonNull String brief) {
        transition(
                scope.scope(),
                new Transition(
                        UUID.randomUUID().toString(),
                        prompt,
                        new JsonValue.ObjectValue(
                                Map.of(
                                        "task",
                                        new JsonValue.StringValue(brief),
                                        "brief",
                                        new JsonValue.StringValue(brief)))));
    }

    public @NonNull List<GroupObservations.View> snapshot() {
        return groups.snapshot().values().stream()
                .map(
                        group ->
                                new GroupObservations.View(
                                        group.groupId().toString(),
                                        group.owner(),
                                        Objects.toString(group.sessionId(), null),
                                        group.leaderId(),
                                        group.state(),
                                        group.dag().nodes()))
                .toList();
    }

    /** Marks the host ready and begins ticking active groups on a daemon scheduler. */
    public synchronized void start() {
        activationReady = true;
        if (scheduler != null) return;
        scheduler =
                Executors.newSingleThreadScheduledExecutor(
                        r -> {
                            var thread = new Thread(r, "builtin-groups");
                            thread.setDaemon(true);
                            return thread;
                        });
        var tick = new GroupTickScheduler(orchestrator, groups);
        scheduler.scheduleWithFixedDelay(
                tick::tickActiveGroups,
                configuration.tickMillis(),
                configuration.tickMillis(),
                TimeUnit.MILLISECONDS);
    }

    private void stopGroups(@NonNull Predicate<Group> selected) {
        RuntimeException failure = null;
        for (var group : groups.snapshot().values()) {
            if (!selected.test(group)) continue;
            try {
                orchestrator.closeGroup(
                        group.groupId(),
                        () -> {
                            spawner.stopRuntime(group.groupId());
                            groups.releaseRuntime(group.groupId());
                        });
            } catch (RuntimeException error) {
                if (failure == null) failure = error;
                else failure.addSuppressed(error);
            }
        }
        if (failure != null) throw failure;
    }

    public void onSessionDeleted(@NonNull SessionDeletedEvent event) {
        var scope = event.scope();
        try {
            stopGroups(
                    group ->
                            scope.owner().equals(group.owner())
                                    && scope.session().equals(String.valueOf(group.sessionId())));
        } finally {
            agents.keySet().removeIf(key -> key.sessionScope().equals(scope));
        }
    }

    public void onAgentTerminated(@NonNull AgentTerminatedEvent event) {
        var scope = event.scope();
        try {
            stopGroups(
                    group ->
                            scope.owner().equals(group.owner())
                                    && scope.agent().equals(group.leaderId())
                                    && scope.session().equals(String.valueOf(group.sessionId())));
        } finally {
            agents.remove(scope);
        }
    }

    public void onUserLogout(@NonNull UserLogoutEvent event) {
        var scope = event.scope();
        try {
            stopGroups(group -> scope.owner().equals(group.owner()));
        } finally {
            agents.keySet().removeIf(key -> key.userScope().equals(scope));
        }
    }

    public synchronized void close() {
        activationReady = false;
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        try {
            stopGroups(group -> true);
        } finally {
            agents.clear();
        }
    }

    // valueOf on the nested NodeState enum is nullable to the NullnessChecker; the guard refines
    // it.
    @SuppressWarnings("ConstantValue")
    static @NonNull DagNode restoreNode(GroupHistoryView.@NonNull Node saved) {
        boolean interrupted =
                Set.of("PENDING", "RUNNING", "CANCEL_REQUESTED", "INTERRUPTED")
                        .contains(saved.state());
        var state =
                interrupted
                        ? DagNode.NodeState.INTERRUPTED
                        : saved.state().equals("COMPLETED")
                                ? DagNode.NodeState.VERIFIED
                                : DagNode.NodeState.valueOf(saved.state());
        if (state == null) throw new IllegalStateException("Unknown saved node state");
        DagNode.NodeResult result =
                state == DagNode.NodeState.VERIFIED
                        ? new DagNode.ResultSuccess(saved.report())
                        : new DagNode.ResultFailure(
                                interrupted
                                        ? "Interrupted by runtime loss; not replayed. Inspect prior"
                                                + " side effects before assigning new work. "
                                                + saved.report()
                                        : saved.report(),
                                List.of());
        return new DagNode(
                saved.id(),
                saved.description(),
                saved.mateId(),
                saved.skillset(),
                Set.copyOf(saved.dependencies()),
                state,
                result,
                saved.retries(),
                saved.dispatchId(),
                saved.requestId());
    }
}
