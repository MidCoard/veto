package top.focess.veto.agent;

import org.jspecify.annotations.NonNull;
import org.springframework.test.util.ReflectionTestUtils;

/** Access to host internals for existing cancellation/approval/compaction integration tests. */
final class AgentLifecycleTestAccess {
    private AgentLifecycleTestAccess() {}

    static @NonNull AgentLifecycle owner(@NonNull AgentRunner runner) {
        var value = ReflectionTestUtils.getField(runner, "lifecycle");
        if (value instanceof AgentLifecycle owner) return owner;
        throw new AssertionError("Missing request lifecycle owner");
    }
}
