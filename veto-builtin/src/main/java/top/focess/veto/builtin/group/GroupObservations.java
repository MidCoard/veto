package top.focess.veto.builtin.group;

import java.util.List;
import org.jspecify.annotations.NonNull;

/** Read-only group facts for observation consumers. */
public interface GroupObservations {
    record View(
            @NonNull String id,
            String owner,
            String sessionId,
            @NonNull String leaderId,
            @NonNull GroupState state,
            @NonNull List<DagNode> nodes) {}

    @NonNull List<View> snapshot();
}
