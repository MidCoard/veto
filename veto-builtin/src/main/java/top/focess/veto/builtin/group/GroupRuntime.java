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
import top.focess.veto.api.agent.tool.ToolDocs;
import top.focess.veto.api.llm.PromptRenderer;
import top.focess.veto.api.llm.ToolResultPresentationMode;
import top.focess.veto.api.plugin.PluginContext;
import top.focess.veto.api.plugin.PluginHost;
import top.focess.veto.api.plugin.agent.AgentHost;
import top.focess.veto.api.plugin.agent.AgentProfile;
import top.focess.veto.api.plugin.contract.AgentConfiguration;
import top.focess.veto.api.plugin.contract.JsonValue;
import top.focess.veto.api.plugin.contract.SessionLifecycle;
import top.focess.veto.api.plugin.storage.PluginStorage;

/** Owns the complete group feature, including activation, policy, persistence and shutdown. */
public final class GroupRuntime
        implements AgentConfiguration, SessionLifecycle, GroupObservations, AutoCloseable {
    private final @NonNull GroupConfig configuration;
    private final PluginHost host;
    private final PromptRenderer prompts;
    private final GroupHistoryStore history;
    private final @NonNull GroupRegistry groups = new GroupRegistry();
    private final @NonNull Blackboard board = new Blackboard();
    private final @NonNull GroupSpawner spawner;
    private final @NonNull GroupOrchestrator orchestrator;
    private final @NonNull GroupOperations operations;
    private final @NonNull Map<String, Context> contexts = new ConcurrentHashMap<>();
    private final @NonNull Map<String, Transition> transitions = new ConcurrentHashMap<>();
    private final @NonNull Set<String> restored = ConcurrentHashMap.newKeySet();
    private ScheduledExecutorService scheduler;
    private volatile boolean activationReady = true;

    public void awaitHostReady() {
        activationReady = false;
    }

    public GroupRuntime(@NonNull PluginContext context) {
        this(context, new JsonValue.ObjectValue(Map.of()));
    }

    public GroupRuntime(@NonNull PluginContext context, JsonValue.@NonNull ObjectValue config) {
        configuration = new GroupConfig(config.values());
        configuration.tickMillis();
        host = context.service(ToolDocs.nonNullClass(PluginHost.class)).orElse(null);
        prompts = context.service(ToolDocs.nonNullClass(PromptRenderer.class)).orElse(null);
        var storage = context.service(ToolDocs.nonNullClass(PluginStorage.class)).orElse(null);
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

    public @NonNull GroupControlCapability operations() {
        return operations;
    }

    public @NonNull DelegationCapability delegation() {
        return operations;
    }

    public @NonNull GroupHistoryStore history() {
        if (history == null) throw new IllegalStateException("Group storage unavailable");
        return history;
    }

    public @NonNull GroupRegistry registry() {
        return groups;
    }

    private static @NonNull String key(@NonNull String session, @NonNull String agent) {
        return session + "/" + agent;
    }

    public synchronized Intent configure(@NonNull Context context) {
        if (!activationReady) throw new IllegalStateException("Host startup is not ready");
        contexts.put(key(context.scope().sessionId(), context.agentId()), context);
        if (history == null) return null;
        history.scope(context.scope());
        if (context.authorizedTools().stream()
                        .noneMatch(
                                tool ->
                                        GroupProfiles.owns(tool, Set.of("create_group"))
                                                && context.base().tools().contains(tool.name()))
                && history.profile(context.scope().sessionId(), context.agentId()) == null)
            return null;
        // Children keep their feature-owned profile; do not accidentally acquire standalone tools.
        var own = history.profile(context.scope().sessionId(), context.agentId());
        if (own != null && own.label().equals("MATE"))
            return new Intent(own, transitionFor(context));
        Group group =
                groups.snapshot().values().stream()
                        .filter(
                                value ->
                                        context.agentId().equals(value.leaderId())
                                                && context.scope()
                                                        .sessionId()
                                                        .equals(String.valueOf(value.sessionId()))
                                                && value.state() != GroupState.DISBANDED)
                        .findFirst()
                        .orElse(null);
        if (group == null
                && !restored.contains(key(context.scope().sessionId(), context.agentId()))) {
            var saved =
                    history.latestSnapshots(context.scope().sessionId()).stream()
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
                            "Team snapshot has no complete member roster; recovery is unavailable. History is retained.");
                UUID id = UUID.fromString(saved.id());
                group =
                        new Group(
                                id,
                                context.agentId(),
                                context.scope().userId(),
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
                                UUID.fromString(context.scope().sessionId()));
                groups.put(group);
            }
        }
        if (group != null && group.state() == GroupState.RECOVERING) {
            spawner.restoreMates(group);
            group = group.withState(GroupState.ACTIVE, Instant.now());
            groups.put(group);
        }
        restored.add(key(context.scope().sessionId(), context.agentId()));
        AgentProfile profile = own == null ? GroupProfiles.standalone(context) : own;
        if (group != null) {
            String profileKey = group.groupId() + "/leader";
            var savedProfile = history.profile(context.scope().sessionId(), profileKey);
            if (savedProfile == null) {
                savedProfile =
                        GroupProfiles.role(
                                context,
                                context.base().name(),
                                context.base().description(),
                                true,
                                configuration,
                                null);
                history.profile(context.scope().sessionId(), profileKey, savedProfile);
            }
            profile = savedProfile;
        }
        return new Intent(profile, transitionFor(context));
    }

    private Transition transitionFor(@NonNull Context context) {
        var pending = transitions.get(key(context.scope().sessionId(), context.agentId()));
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
        var context = contexts.get(key(session.toString(), group.leaderId()));
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
            transitions.put(
                    key(session.toString(), id),
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
        var context = contexts.get(key(scope.sessionId(), scope.agentId()));
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
                        context.scope().userId(),
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
        transitions.put(
                key(scope.sessionId(), scope.agentId()),
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

    public void onSessionClosed(@NonNull String owner, @NonNull String session) {
        try {
            stopGroups(
                    group ->
                            owner.equals(group.owner())
                                    && session.equals(String.valueOf(group.sessionId())));
        } finally {
            restored.removeIf(key -> key.startsWith(session + "/"));
            contexts.keySet().removeIf(key -> key.startsWith(session + "/"));
            transitions.keySet().removeIf(key -> key.startsWith(session + "/"));
        }
    }

    public void onAgentTerminated(
            @NonNull String owner, @NonNull String session, @NonNull String agent) {
        try {
            stopGroups(
                    group ->
                            owner.equals(group.owner())
                                    && agent.equals(group.leaderId())
                                    && session.equals(String.valueOf(group.sessionId())));
        } finally {
            String key = key(session, agent);
            contexts.remove(key);
            transitions.remove(key);
            restored.remove(key);
        }
    }

    public void onOwnerClosed(@NonNull String owner) {
        var owned =
                contexts.entrySet().stream()
                        .filter(entry -> owner.equals(entry.getValue().owner()))
                        .map(Map.Entry::getKey)
                        .toList();
        try {
            stopGroups(group -> owner.equals(group.owner()));
        } finally {
            for (var key : owned) {
                contexts.remove(key);
                transitions.remove(key);
                restored.remove(key);
            }
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
            contexts.clear();
            transitions.clear();
            restored.clear();
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
                                        ? "Interrupted by runtime loss; not replayed. Inspect prior side effects before assigning new work. "
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
