package top.focess.veto.group;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

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

    public void put(@NonNull Group group) {
        groups.put(group.groupId(), group);
    }

    public Group get(@NonNull UUID groupId) {
        return groups.get(groupId);
    }

    public void disband(@NonNull UUID groupId, @NonNull Instant when) {
        Group g = groups.get(groupId);
        if (g == null) {
            return;
        }
        groups.put(groupId, g.withState(Group.GroupState.DISBANDED, when));
    }

    public boolean remove(@NonNull UUID groupId) {
        return groups.remove(groupId) != null;
    }

    public @NonNull Map<UUID, Group> snapshot() {
        return Map.copyOf(groups);
    }
}
