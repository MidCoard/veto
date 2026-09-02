package top.focess.veto.group;

import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * A single append-only message on the group Blackboard, ordered by {@code turnSeq}. The payload is
 * always a small value — paths, status strings, short feedback, or node-task instructions — never
 * file contents.
 */
public record BlackboardMessage(
        @NonNull String messageId,
        @NonNull UUID groupId,
        @NonNull String senderId, // Mate id or "LEADER"
        @NonNull String receiverId, // "LEADER" for Mate posts; a Mate id for Leader dispatches
        @NonNull MessageType type,
        @NonNull String payload,
        long turnSeq) {

    public enum MessageType {
        /** Leader → Mate: a task or revision instruction (the Leader's authored text). */
        TASK_DISPATCH,
        /** Mate → Leader: "I produced X at workspace path P" (a file path). */
        ARTIFACT_REF,
        /** Mate → Leader: "test/error log at path P" (a file path — content stays out). */
        LOG_REF,
        /** Mate → Leader: short text — e.g. a verifier's failure summary. */
        FEEDBACK,
        /** Node status update (PENDING/ASSIGNED/RUNNING/VERIFIED/FAILED/STALE). */
        STATUS,
        /** Mate → Leader: "verified, accepted" — terminates a verify loop. */
        ACCEPT
    }
}
