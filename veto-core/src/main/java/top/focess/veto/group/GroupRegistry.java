package top.focess.veto.group;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import top.focess.veto.bus.SessionInvalidations;

/**
 * The in-process registry of active Groups. {@code create_group} creates and stores a Group; {@code
 * disband_group} marks it disbanded while retaining its Blackboard for audit.
 *
 * <p>Groups currently live in the JVM process. A persistent implementation would store state, DAG,
 * and member metadata so a Leader can be reconstructed after a crash.
 */
@Component
public class GroupRegistry {

    private final @NonNull ConcurrentMap<UUID, Group> groups = new ConcurrentHashMap<>();

    private GroupHistoryStore historyStore;
    private SessionInvalidations invalidations;

    @Autowired
    public void attachInvalidations(@NonNull SessionInvalidations invalidations) {
        this.invalidations = invalidations;
    }

    @Autowired
    public void attachHistory(@NonNull GroupHistoryStore historyStore) {
        this.historyStore = historyStore;
    }

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
        if (invalidations != null && group.sessionId() != null && previous != group)
            invalidations.changed(group.sessionId(), "groups");
    }

    public Group get(@NonNull UUID groupId) {
        return groups.get(groupId);
    }

    public void disband(@NonNull UUID groupId, @NonNull Instant when) {
        Group g = groups.get(groupId);
        if (g == null) {
            return;
        }
        put(g.withState(Group.GroupState.DISBANDED, when));
    }

    public boolean remove(@NonNull UUID groupId) {
        Group removed = groups.remove(groupId);
        if (removed == null) return false;
        removed.blackboard().signalChange();
        if (invalidations != null && removed.sessionId() != null)
            invalidations.changed(removed.sessionId(), "groups");
        return true;
    }

    public @NonNull Map<UUID, Group> snapshot() {
        return Map.copyOf(groups);
    }
}
