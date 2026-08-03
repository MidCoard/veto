package top.focess.veto.group;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The in-process registry of active Groups. {@code create_group} creates a Group and stores it
 * here; {@code disband_group} removes it (the Blackboard is retained for audit per {@code
 * blackboard.md §5.2}).
 *
 * <p>For the MVP path, groups live in the JVM process. Production would persist the Group metadata
 * (state + DAG + members) so a Leader crash can be reconstructed.
 */
@Component
public class GroupRegistry {

    private final @NonNull ConcurrentMap<UUID, Group> groups = new ConcurrentHashMap<>();

    public void put(@NonNull Group group) {
        groups.put(group.groupId(), group);
    }

    public @Nullable Group get(@NonNull UUID groupId) {
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
