package top.focess.veto.builtin.group;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.plugin.agent.AgentHost;

/** Plugin-owned member lifetime; all execution authority is carried by host child handles. */
public final class GroupSpawner implements GroupOrchestrator.MateProvisioner, AutoCloseable {
    @FunctionalInterface
    public interface AgentFactory {
        AgentHost.@NonNull Child open(
                @NonNull Group group,
                @NonNull String id,
                @NonNull String name,
                @NonNull String responsibility);

        default AgentHost.@NonNull Child openSkilled(
                @NonNull Group group, @NonNull String id, @NonNull String skillset) {
            return open(group, id, skillset, skillset);
        }
    }

    private final @NonNull GroupRegistry registry;
    private final @NonNull Blackboard board;
    private final @NonNull AgentFactory factory;
    private final @NonNull Map<UUID, Map<String, MateAgent>> live = new ConcurrentHashMap<>();

    public GroupSpawner(
            @NonNull GroupRegistry registry,
            @NonNull Blackboard board,
            @NonNull AgentFactory factory) {
        this.registry = registry;
        this.board = board;
        this.factory = factory;
    }

    public @NonNull String provision(@NonNull UUID group, @NonNull String responsibility) {
        var current = registry.get(group);
        if (current == null) throw new IllegalArgumentException("Unknown group");
        String id = UUID.randomUUID().toString();
        start(current, id, responsibility, responsibility, responsibility);
        return id;
    }

    public @NonNull String createNamedMate(
            @NonNull UUID groupId, @NonNull String name, @NonNull String responsibility) {
        Group group = registry.get(groupId);
        if (group == null) throw new IllegalArgumentException("Unknown group");
        String id = UUID.randomUUID().toString();
        start(group, id, name, responsibility, null);
        return id;
    }

    private synchronized void start(
            @NonNull Group group,
            @NonNull String id,
            @NonNull String name,
            @NonNull String responsibility,
            String skillset) {
        var members = live.computeIfAbsent(group.groupId(), ignored -> new ConcurrentHashMap<>());
        if (members.containsKey(id)) return;
        var child =
                skillset == null
                        ? factory.open(group, id, name, responsibility)
                        : factory.openSkilled(group, id, skillset);
        var mate = new MateAgent(id, group.groupId(), responsibility, child, board);
        members.put(id, mate);
        mate.start();
    }

    public void restoreMates(@NonNull Group group) {
        group.mates().forEach((id, responsibility) -> start(group, id, id, responsibility, null));
    }

    boolean stopMateAndConfirm(@NonNull UUID group, @NonNull String id) {
        var mate = live.getOrDefault(group, Map.of()).get(id);
        if (mate == null) return false;
        mate.stop();
        try {
            return mate.awaitTermination(Duration.ofSeconds(2));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    boolean cancelDispatch(
            @NonNull UUID group,
            @NonNull String id,
            @NonNull String dispatch,
            @NonNull Duration timeout) {
        var mate = live.getOrDefault(group, Map.of()).get(id);
        if (mate == null) return false;
        try {
            return mate.cancelDispatch(dispatch, timeout);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    void forgetStoppedMate(@NonNull UUID group, @NonNull String id) {
        var members = live.get(group);
        if (members != null) members.remove(id);
    }

    public void disband(@NonNull UUID group) {
        for (var id : List.copyOf(live.getOrDefault(group, Map.of()).keySet()))
            if (!stopMateAndConfirm(group, id))
                throw new IllegalStateException("Member stop is not yet confirmed: " + id);
        live.remove(group);
        registry.disband(group, Instant.now());
    }

    public void stopRuntime(@NonNull UUID group) {
        var members = live.get(group);
        if (members == null) return;
        var closing = Map.copyOf(members);
        RuntimeException failure = null;
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        for (var mate : closing.values()) {
            try {
                mate.stop();
            } catch (RuntimeException error) {
                failure = error;
            }
        }
        for (var entry : closing.entrySet()) {
            try {
                long remaining = Math.max(0, deadline - System.nanoTime());
                if (entry.getValue().awaitTermination(Duration.ofNanos(remaining)))
                    members.remove(entry.getKey(), entry.getValue());
                else
                    failure =
                            new IllegalStateException(
                                    "Member stop is not yet confirmed: " + entry.getKey());
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                failure =
                        new IllegalStateException(
                                "Interrupted while waiting for member shutdown", error);
                break;
            }
        }
        if (members.isEmpty()) live.remove(group, members);
        if (failure != null) throw failure;
    }

    public void close() {
        List.copyOf(live.keySet()).forEach(this::stopRuntime);
    }
}
