package top.focess.veto.agent;

import org.jspecify.annotations.NonNull;
import top.focess.veto.bus.DeltaFrame;

/** Narrow core event publication port used by an agent runtime. */
@FunctionalInterface
public interface AgentEventSink {
    void publish(@NonNull DeltaFrame frame);

    static @NonNull AgentEventSink none() {
        return frame -> {};
    }
}
