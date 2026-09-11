package top.focess.veto.agent;

import org.jspecify.annotations.NonNull;

/** Identity of work interrupted by runtime loss, reconstructed from the owned team snapshot. */
public record RecoveredTask(
        @NonNull String groupId, @NonNull String nodeId, String dispatchId, String requestId) {}
