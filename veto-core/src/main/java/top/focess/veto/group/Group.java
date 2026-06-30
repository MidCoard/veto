package top.focess.veto.group;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * A group is a Leader-Mate collaboration spawned via {@code create_group} (delegation_spawning.md).
 * It owns its Blackboard, its ExecutionDAG, and the set of Mates under the Leader.
 *
 * <p>A Group's lifetime:
 *
 * <ol>
 *   <li>{@code create_group} → state {@code ACTIVE}; Leader initialized, DAG authored.
 *   <li>Engine drives the DAG: dispatches dispatchable nodes, ingests Blackboard messages, runs the
 *       verify loop (leader_mate_topology.md §3).
 *   <li>All nodes {@code VERIFIED} → final node (Leader synthesizes the result).
 *   <li>{@code disband_group} → state {@code DISBANDED}; Blackboard retained for audit.
 * </ol>
 */
public record Group(
        @NonNull UUID groupId,
        @NonNull String leaderId,
        @NonNull String userId,
        @NonNull String contextBrief,
        @NonNull ExecutionDag dag,
        @NonNull Blackboard blackboard,
        @NonNull Map<String, String> mates, // mateId → skillset
        @NonNull GroupState state,
        @NonNull Instant createdAt,
        @Nullable Instant disbandedAt) {

    public Group {
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(leaderId, "leaderId");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(dag, "dag");
        Objects.requireNonNull(blackboard, "blackboard");
        mates = mates == null ? Map.of() : Map.copyOf(mates);
    }

    public enum GroupState {
        ACTIVE,
        DISBANDED
    }

    public static @NonNull Group create(
            @NonNull String leaderId,
            @NonNull String userId,
            @Nullable String contextBrief,
            @NonNull Blackboard blackboard,
            @NonNull ExecutionDag dag) {
        UUID id = UUID.randomUUID();
        return new Group(
                id,
                leaderId,
                userId,
                contextBrief == null ? "" : contextBrief,
                dag.groupId().equals(id) ? dag : new ExecutionDag(id, dag.nodes()),
                blackboard,
                new LinkedHashMap<>(),
                GroupState.ACTIVE,
                Instant.now(),
                null);
    }

    public @NonNull Group withDag(@NonNull ExecutionDag newDag) {
        return new Group(
                groupId,
                leaderId,
                userId,
                contextBrief,
                newDag,
                blackboard,
                mates,
                state,
                createdAt,
                disbandedAt);
    }

    public @NonNull Group withState(@NonNull GroupState newState, @NonNull Instant when) {
        return new Group(
                groupId,
                leaderId,
                userId,
                contextBrief,
                dag,
                blackboard,
                mates,
                newState,
                createdAt,
                newState == GroupState.DISBANDED ? when : disbandedAt);
    }

    public @NonNull Group withMate(@NonNull String mateId, @NonNull String skillset) {
        Map<String, String> next = new LinkedHashMap<>(mates);
        next.put(mateId, skillset);
        return new Group(
                groupId,
                leaderId,
                userId,
                contextBrief,
                dag,
                blackboard,
                next,
                state,
                createdAt,
                disbandedAt);
    }

    public @NonNull Group withoutMate(@NonNull String mateId) {
        if (!mates.containsKey(mateId)) {
            return this;
        }
        Map<String, String> next = new LinkedHashMap<>(mates);
        next.remove(mateId);
        return new Group(
                groupId,
                leaderId,
                userId,
                contextBrief,
                dag,
                blackboard,
                next,
                state,
                createdAt,
                disbandedAt);
    }

    public boolean isActive() {
        return state == GroupState.ACTIVE;
    }
}
