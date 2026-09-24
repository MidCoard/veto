package top.focess.veto.api.plugin.agent;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.AgentResult;
import top.focess.veto.api.agent.AgentState;
import top.focess.veto.api.plugin.storage.PluginStorage;

/** Session-bound execution authority. Plugins cannot choose an owner or host workspace path. */
public interface AgentHost {
    default @NonNull IsolatedAgent isolate(
            IsolatedAgent.@NonNull Spec spec, IsolatedAgent.@NonNull Factory tools) {
        throw new SecurityException("Isolated execution is unavailable");
    }

    @NonNull Session session(PluginStorage.@NonNull SessionScope scope);

    interface Session {
        @NonNull String id();

        /** Creates or restores this plugin's child identity without replaying interrupted work. */
        @NonNull Child open(
                @NonNull String id, @NonNull String parentId, @NonNull AgentProfile profile);
    }

    interface Child {
        @NonNull String id();

        @NonNull AgentState state();

        @NonNull Request submit(@NonNull String prompt);

        void close();

        boolean awaitTermination(@NonNull Duration timeout) throws InterruptedException;
    }

    interface Request {
        @NonNull String id();

        /** An isolated view; completing or cancelling it never changes host execution. */
        @NonNull CompletableFuture<AgentResult> result();

        /** Actual execution exit, also exposed as an isolated view. */
        @NonNull CompletableFuture<Boolean> settled();

        boolean cancel(@NonNull Duration timeout) throws InterruptedException;
    }
}
