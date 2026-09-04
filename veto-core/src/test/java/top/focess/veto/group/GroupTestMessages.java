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
                        0));
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
                        0));
    }
}
