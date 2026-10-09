package top.focess.veto.agent;

import org.jspecify.annotations.NonNull;
import top.focess.veto.contract.EventFrame;

/**
 * Narrow core publication port invoked inline by an agent runtime's producing threads.
 *
 * <p>The runtime may call a shared sink concurrently; an implementation owns any serialization,
 * buffering, and transport delivery it requires. Returning does not imply remote receipt. The frame
 * owns its attribute map, but nested JSON nodes are shared and must be treated as read-only.
 */
@FunctionalInterface
public interface AgentEventSink {
    /** Publish one delta frame to the underlying transport. */
    void publish(@NonNull EventFrame frame);

    /** A sink that discards every frame. */
    static @NonNull AgentEventSink none() {
        return frame -> {};
    }
}
