package top.focess.veto.agent;

import org.jspecify.annotations.NonNull;
import top.focess.veto.bus.DeltaFrame;

/** Narrow core event publication port used by an agent runtime. */
@FunctionalInterface
public interface AgentEventSink {
    /** Publish one delta frame to the underlying transport. */
    void publish(@NonNull DeltaFrame frame);

    /** A sink that discards every frame. */
    static @NonNull AgentEventSink none() {
        return frame -> {};
    }
}
