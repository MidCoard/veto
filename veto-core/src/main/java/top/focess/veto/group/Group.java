package top.focess.veto.group;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.workspace.Workspace;
import top.focess.veto.llm.core.ToolResultPresentationMode;

/**
 * A group is a Leader-Mate collaboration spawned via {@code create_group}. It owns its Blackboard,
 * its ExecutionDAG, and the set of Mates under the Leader.
 *
 * <p>A Group's lifetime:
 *
 * <ol>
 *   <li>{@code create_group} → state {@code ACTIVE}; Leader initialized, DAG authored.
 *   <li>The engine drives the DAG: dispatches ready nodes, ingests Blackboard messages, and
 *       verifies completed work.
 *   <li>All nodes {@code VERIFIED} → state {@code COMPLETED}; the Leader inspects the reports and
 *       synthesizes the result.
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
        Instant disbandedAt,
        String owner,
        Workspace workspace,
        @NonNull ToolResultPresentationMode toolResultPresentation) {

    public Group {
        mates = Map.copyOf(mates);
    }

    public enum GroupState {
        ACTIVE,
        COMPLETED,
        DISBANDED
    }

    public static @NonNull Group create(
            @NonNull String leaderId,
            @NonNull String userId,
            String contextBrief,
            @NonNull Blackboard blackboard,
            @NonNull ExecutionDag dag) {
        return create(
                leaderId,
                userId,
                contextBrief,
                blackboard,
                dag,
                null,
                null,
                ToolResultPresentationMode.BASIC);
    }

    /**
     * Creates a Group carrying the session {@code owner} (the username whose active model-tier
     * profile resolves every Mate / Leader tier in this group). The owner is stamped at {@code
     * create_group} time from the calling agent's {@link top.focess.veto.agent.mcp.ToolCallContext}
     * and read back when the {@link GroupTickScheduler} lazily provisions Mates on its own thread -
     * where no tool-call scope, and therefore no thread-local owner, exists.
     */
    public static @NonNull Group create(
            @NonNull String leaderId,
            @NonNull String userId,
            String contextBrief,
            @NonNull Blackboard blackboard,
            @NonNull ExecutionDag dag,
            String owner) {
        return create(
                leaderId,
                userId,
                contextBrief,
                blackboard,
                dag,
                owner,
                null,
                ToolResultPresentationMode.BASIC);
    }

    public static @NonNull Group create(
            @NonNull String leaderId,
            @NonNull String userId,
            String contextBrief,
            @NonNull Blackboard blackboard,
            @NonNull ExecutionDag dag,
            String owner,
            @NonNull ToolResultPresentationMode toolResultPresentation) {
        return create(
                leaderId,
                userId,
                contextBrief,
                blackboard,
                dag,
                owner,
                null,
                toolResultPresentation);
    }

    public static @NonNull Group create(
            @NonNull String leaderId,
            @NonNull String userId,
            String contextBrief,
            @NonNull Blackboard blackboard,
            @NonNull ExecutionDag dag,
            String owner,
            Workspace workspace,
            @NonNull ToolResultPresentationMode toolResultPresentation) {
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
                null,
                owner,
                workspace,
                toolResultPresentation);
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
                disbandedAt,
                owner,
                workspace,
                toolResultPresentation);
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
                newState == GroupState.DISBANDED ? when : disbandedAt,
                owner,
                workspace,
                toolResultPresentation);
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
                disbandedAt,
                owner,
                workspace,
                toolResultPresentation);
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
                disbandedAt,
                owner,
                workspace,
                toolResultPresentation);
    }

    public boolean isActive() {
        return state == GroupState.ACTIVE;
    }
}
