package top.focess.veto.group;

import java.util.Objects;
import java.util.UUID;

/**
 * A single message on the group Blackboard (blackboard.md §2). Append-only, ordered by {@code
 * turnSeq}. The payload is always a small value — paths, status strings, short feedback, or
 * node-task instructions — never file contents.
 */
public record BlackboardMessage(
        String messageId,
        UUID groupId,
        String senderId, // Mate id or "LEADER"
        String receiverId, // "LEADER" for Mate posts; a Mate id for Leader dispatches
        MessageType type,
        String payload,
        long turnSeq) {

    public BlackboardMessage {
        Objects.requireNonNull(messageId, "messageId");
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(senderId, "senderId");
        Objects.requireNonNull(receiverId, "receiverId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(payload, "payload");
    }

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
