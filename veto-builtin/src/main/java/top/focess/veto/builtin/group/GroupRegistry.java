package top.focess.veto.builtin.group;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.plugin.PluginHost;
import top.focess.veto.api.plugin.Scope;
import top.focess.veto.api.plugin.contract.JsonValue;
import top.focess.veto.api.plugin.contract.JsonValues;

/**
 * The in-process registry of active Groups. {@code create_group} creates and stores a Group; {@code
 * disband_group} marks it disbanded while retaining its Blackboard for audit.
 *
 * <p>Groups currently live in the JVM process. A persistent implementation would store state, DAG,
 * and member metadata so a Leader can be reconstructed after a crash.
 *
 * <p>Mutations, history writes and frontend publications share this registry's monitor. Reads of
 * live groups remain nonblocking for Blackboard wait predicates; change signals occur after
 * releasing the registry monitor so those predicates cannot invert the lock order.
 */
public class GroupRegistry {

    private final @NonNull ConcurrentMap<UUID, Group> groups = new ConcurrentHashMap<>();
    private final @NonNull ObjectMapper mapper = new ObjectMapper();

    private GroupHistoryStore historyStore;
    private PluginHost host;
    private long revision;

    /** Registers the host that publishes builtin group data. */
    public synchronized void attachHost(@NonNull PluginHost host) {
        this.host = host;
    }

    /** Registers the store that receives a history snapshot on every meaningful change. */
    public synchronized void attachHistory(@NonNull GroupHistoryStore historyStore) {
        this.historyStore = historyStore;
    }

    /** Stores the group, persisting history before publishing its current summary. */
    public void put(@NonNull Group group) {
        try {
            synchronized (this) {
                Group previous = groups.get(group.groupId());
                GroupHistoryStore store = historyStore;
                if (store != null
                        && (previous == null
                                || !previous.dag().equals(group.dag())
                                || !previous.mates().equals(group.mates())
                                || previous.state() != group.state())) store.save(group);
                groups.put(group.groupId(), group);
                if (previous != group) publish(group, true);
            }
        } finally {
            group.blackboard().signalChange();
        }
    }

    /** Returns the registered group, or {@code null} when the id is unknown. */
    public Group get(@NonNull UUID groupId) {
        return groups.get(groupId);
    }

    /** Transitions the group to {@link GroupState#DISBANDED}; unknown ids are ignored. */
    public void disband(@NonNull UUID groupId, @NonNull Instant when) {
        Group g = groups.get(groupId);
        if (g == null) {
            return;
        }
        put(g.withState(GroupState.DISBANDED, when));
    }

    /** Drops revoked runtime state without publishing through its closed host grant. */
    public boolean releaseRuntime(@NonNull UUID groupId) {
        Group removed;
        synchronized (this) {
            removed = groups.remove(groupId);
            if (removed != null) revision++;
        }
        if (removed == null) return false;
        removed.blackboard().signalChange();
        return true;
    }

    /** Removes live execution while retaining and publishing its durable history row. */
    public boolean remove(@NonNull UUID groupId) {
        Group removed = null;
        try {
            synchronized (this) {
                removed = groups.remove(groupId);
                if (removed == null) return false;
                publish(removed, false);
            }
        } finally {
            if (removed != null) removed.blackboard().signalChange();
        }
        return true;
    }

    /** Revision paired with frontend reads while holding the registry monitor. */
    public synchronized long revision() {
        return revision;
    }

    private void publish(@NonNull Group group, boolean live) {
        long current = ++revision;
        var publisher = host;
        var session = group.sessionId();
        if (publisher == null || session == null) return;
        String state =
                !live
                                && (group.state() == GroupState.ACTIVE
                                        || group.state() == GroupState.RECOVERING)
                        ? "INTERRUPTED"
                        : group.state().name();
        var data = mapper.createObjectNode().put("revision", current);
        data.putObject("group")
                .put("id", group.groupId().toString())
                .put("leaderId", group.leaderId())
                .put("state", state)
                .put("live", live)
                .put("historical", false)
                .put("createdAt", group.createdAt().toString())
                .put("briefLength", group.contextBrief().length())
                .put("nodeCount", group.dag().nodes().size());
        publisher.publish(
                new Scope.SessionScope(group.userId(), session.toString()),
                "group_changed",
                (JsonValue.ObjectValue) JsonValues.from(data));
    }

    /** Immutable copy of all registered groups keyed by id. */
    public @NonNull Map<UUID, Group> snapshot() {
        return Map.copyOf(groups);
    }
}
