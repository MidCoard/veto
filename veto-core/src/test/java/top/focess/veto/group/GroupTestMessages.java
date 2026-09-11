package top.focess.veto.group;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

final class GroupTestMessages {

    private GroupTestMessages() {}

    static void accept(
            @NonNull Blackboard blackboard,
            @NonNull UUID groupId,
            @NonNull String mateId,
            @NonNull String nodeId) {
        String summary = "Synthetic Mate completion.";
        String payload =
                nodeId
                        + ":accept-base64:"
                        + Base64.getEncoder()
                                .encodeToString(summary.getBytes(StandardCharsets.UTF_8));
        blackboard.post(
                new BlackboardMessage(
                        UUID.randomUUID().toString(),
                        groupId,
                        mateId,
                        "LEADER",
                        BlackboardMessage.MessageType.ACCEPT,
                        payload,
                        0,
                        dispatchId(blackboard, groupId, nodeId)));
    }

    static void feedback(
            @NonNull Blackboard blackboard,
            @NonNull UUID groupId,
            @NonNull String mateId,
            @NonNull String nodeId,
            @NonNull String feedback) {
        blackboard.post(
                new BlackboardMessage(
                        UUID.randomUUID().toString(),
                        groupId,
                        mateId,
                        "LEADER",
                        BlackboardMessage.MessageType.FEEDBACK,
                        nodeId + ":feedback:" + feedback,
                        0,
                        dispatchId(blackboard, groupId, nodeId)));
    }

    static String dispatchId(
            @NonNull Blackboard blackboard, @NonNull UUID groupId, @NonNull String nodeId) {
        String dispatchId = null;
        for (BlackboardMessage message : blackboard.readAll(groupId)) {
            if (message.type() == BlackboardMessage.MessageType.TASK_DISPATCH
                    && message.payload().startsWith(nodeId + ":")) {
                dispatchId = message.dispatchId();
            }
        }
        return dispatchId;
    }
}
