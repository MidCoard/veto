package top.focess.veto.builtin.group;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.plugin.PluginHost;

/**
 * The in-process registry of active Groups. {@code create_group} creates and stores a Group; {@code
 * disband_group} marks it disbanded while retaining its Blackboard for audit.
 *
 * <p>Groups currently live in the JVM process. A persistent implementation would store state, DAG,
 * and member metadata so a Leader can be reconstructed after a crash.
 */
public class GroupRegistry {

    private final @NonNull ConcurrentMap<UUID, Group> groups = new ConcurrentHashMap<>();

    private GroupHistoryStore historyStore;
    private PluginHost invalidations;

    /** Registers the host used to invalidate cached frontend group views. */
    public void attachInvalidations(@NonNull PluginHost invalidations) {
        this.invalidations = invalidations;
    }

    /** Registers the store that receives a history snapshot on every meaningful change. */
    public void attachHistory(@NonNull GroupHistoryStore historyStore) {
        this.historyStore = historyStore;
    }

    /** Stores the group, persisting history and invalidating frontends when it changed. */
    public void put(@NonNull Group group) {
        Group previous = groups.get(group.groupId());
        GroupHistoryStore store = historyStore;
        if (store != null
                && (previous == null
                        || !previous.dag().equals(group.dag())
                        || !previous.mates().equals(group.mates())
                        || previous.state() != group.state())) store.save(group);
        groups.put(group.groupId(), group);
        group.blackboard().signalChange();
        PluginHost events = invalidations;
        UUID sessionId = group.sessionId();
        if (events != null && sessionId != null && previous != group)
            events.invalidate(sessionId.toString(), "groups");
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

    /** Drops the group without frontend invalidation; true when it was registered. */
    public boolean releaseRuntime(@NonNull UUID groupId) {
        var removed = groups.remove(groupId);
        if (removed == null) return false;
        removed.blackboard().signalChange();
        return true;
    }

    /** Removes the group and invalidates its frontend views; true when it was registered. */
    public boolean remove(@NonNull UUID groupId) {
        Group removed = groups.remove(groupId);
        if (removed == null) return false;
        removed.blackboard().signalChange();
        PluginHost events = invalidations;
        UUID sessionId = removed.sessionId();
        if (events != null && sessionId != null) events.invalidate(sessionId.toString(), "groups");
        return true;
    }

    /** Immutable copy of all registered groups keyed by id. */
    public @NonNull Map<UUID, Group> snapshot() {
        return Map.copyOf(groups);
    }
}
