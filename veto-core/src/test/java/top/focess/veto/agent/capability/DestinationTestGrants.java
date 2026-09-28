package top.focess.veto.agent.capability;

import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.tool.ToolCallContextHolder;
import top.focess.veto.api.http.ApprovedHttpDestination;
import top.focess.veto.api.http.HttpDocument;
import top.focess.veto.api.plugin.agent.IsolatedAgent;

/** Uses the real host grant with a deterministic transport in reader integration tests. */
public final class DestinationTestGrants {
    private DestinationTestGrants() {}

    public static @NonNull ApprovedHttpDestination wrap(
            @NonNull ApprovedHttpDestination source, @NonNull Runnable afterClose) {
        var context = ToolCallContextHolder.get();
        if (context == null) throw new AssertionError("Missing approved parent call");
        var grant = new HttpDestinationGrant(deadline -> source.fetch(), context);
        return new ApprovedHttpDestination() {
            public void bind(IsolatedAgent.@NonNull Runtime runtime, @NonNull String operation) {
                grant.bind(runtime, operation);
            }

            public @NonNull HttpDocument fetch() {
                return grant.fetch();
            }

            public void publish(@NonNull IsolatedAgent child) {
                grant.publish(child);
            }

            public void close() {
                try {
                    grant.close();
                    source.close();
                } finally {
                    afterClose.run();
                }
            }
        };
    }
}
