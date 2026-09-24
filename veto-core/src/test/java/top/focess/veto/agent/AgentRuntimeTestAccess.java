package top.focess.veto.agent;

import org.jspecify.annotations.NonNull;
import org.springframework.test.util.ReflectionTestUtils;

/** Access to host internals for existing cancellation/approval/compaction integration tests. */
final class AgentRuntimeTestAccess {
    private AgentRuntimeTestAccess() {}

    static @NonNull AgentRuntimeState state(@NonNull AgentRunner runner) {
        var value = ReflectionTestUtils.getField(runner, "runtime");
        if (value instanceof AgentRuntimeState state) return state;
        throw new AssertionError("Missing runner runtime");
    }
}
