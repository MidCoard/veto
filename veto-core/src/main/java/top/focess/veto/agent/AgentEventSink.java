package top.focess.veto.agent;

import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.intercept.VetoPrompt;
import top.focess.veto.api.plugin.Scope;
import top.focess.veto.contract.Frame;
import top.focess.veto.session.ProtocolConnection;

/**
 * Narrow core publication port invoked inline by an agent runtime's producing threads.
 *
 * <p>The runtime may call a shared sink concurrently; an implementation owns any serialization,
 * buffering, and transport delivery it requires. Returning does not imply remote receipt. Nested
 * JSON payloads must be treated as read-only.
 */
@FunctionalInterface
public interface AgentEventSink {
    /** Publish one session frame to the underlying transport. */
    void publish(Frame.@NonNull ScopedFrame frame);

    default void publish(Scope.@NonNull AgentScope scope, @NonNull VetoPrompt prompt) {
        publish(ProtocolConnection.vetoFrame(scope, prompt));
    }

    /** A sink that discards every frame. */
    static @NonNull AgentEventSink none() {
        return frame -> {};
    }
}
