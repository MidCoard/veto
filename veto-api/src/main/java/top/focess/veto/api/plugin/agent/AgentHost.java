package top.focess.veto.api.plugin.agent;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.AgentResult;
import top.focess.veto.api.agent.AgentState;
import top.focess.veto.api.plugin.storage.PluginStorage;

/** Session-bound execution authority. Plugins cannot choose an owner or host workspace path. */
public interface AgentHost {
    /**
     * Creates an ephemeral agent for the current authorized tool invocation.
     *
     * @param spec requested execution contract
     * @param tools factory for invocation-scoped tools
     * @return the admitted isolated agent
     * @throws SecurityException when isolated execution is unavailable or unauthorized
     */
    default @NonNull IsolatedAgent isolate(
            IsolatedAgent.@NonNull Spec spec, IsolatedAgent.@NonNull Factory tools) {
        throw new SecurityException("Isolated execution is unavailable");
    }

    /**
     * Returns a session-bound agent namespace after validating the host-issued scope.
     *
     * @param scope host-issued session scope
     * @return the bound agent namespace
     */
    @NonNull Session session(PluginStorage.@NonNull SessionScope scope);

    /** Agent operations bound to one authorized session. */
    interface Session {
        /**
         * Returns the host session ID represented by this handle.
         *
         * @return the session ID
         */
        @NonNull String id();

        /**
         * Creates or restores this plugin's child identity without replaying interrupted work.
         *
         * @param id child identity within the session
         * @param parentId parent agent identity
         * @param profile requested child-agent profile
         * @return the opened child agent
         */
        @NonNull Child open(
                @NonNull String id, @NonNull String parentId, @NonNull AgentProfile profile);
    }

    /** Plugin-owned child agent within a bound session. */
    interface Child {
        /**
         * Returns the child identity within its session.
         *
         * @return the child identity
         */
        @NonNull String id();

        /**
         * Returns the latest host-observed execution state.
         *
         * @return the latest state
         */
        @NonNull AgentState state();

        /**
         * Submits a prompt for host-authorized execution.
         *
         * @param prompt prompt to execute
         * @return the submitted request
         */
        @NonNull Request submit(@NonNull String prompt);

        /** Closes admission and requests child termination. */
        void close();

        /**
         * Waits up to {@code timeout} for actual termination.
         *
         * @param timeout maximum time to wait
         * @return {@code true} when the child terminated before the deadline
         * @throws InterruptedException when the waiting thread is interrupted
         */
        boolean awaitTermination(@NonNull Duration timeout) throws InterruptedException;
    }

    /** One submitted child-agent request. */
    interface Request {
        /**
         * Returns the durable request identity assigned by the host.
         *
         * @return the request identity
         */
        @NonNull String id();

        /**
         * Returns an isolated result view; completing or cancelling it never changes host
         * execution.
         *
         * @return a future containing the agent result
         */
        @NonNull CompletableFuture<AgentResult> result();

        /**
         * Returns actual execution exit, also exposed as an isolated view.
         *
         * @return a future completed with whether execution settled normally
         */
        @NonNull CompletableFuture<Boolean> settled();

        /**
         * Requests cancellation and waits up to {@code timeout} for execution to settle.
         *
         * @param timeout maximum time to wait
         * @return {@code true} when execution settled before the deadline
         * @throws InterruptedException when the waiting thread is interrupted
         */
        boolean cancel(@NonNull Duration timeout) throws InterruptedException;
    }
}
