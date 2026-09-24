package top.focess.veto.agent.capability;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.framework.qual.DefaultQualifier;
import org.checkerframework.framework.qual.TypeUseLocation;
import top.focess.veto.agent.tool.ToolCallContextHolder;
import top.focess.veto.api.http.ApprovedHttpDestination;
import top.focess.veto.api.http.HttpDocument;
import top.focess.veto.api.plugin.agent.IsolatedAgent;

/** Uses the real host grant with a deterministic transport in reader integration tests. */
@DefaultQualifier(
        value = NonNull.class,
        locations = {
            TypeUseLocation.FIELD,
            TypeUseLocation.PARAMETER,
            TypeUseLocation.RETURN,
            TypeUseLocation.UPPER_BOUND
        })
public final class DestinationTestGrants {
    private DestinationTestGrants() {}

    public static ApprovedHttpDestination wrap(
            ApprovedHttpDestination source, Runnable afterClose) {
        var context = ToolCallContextHolder.get();
        if (context == null) throw new AssertionError("Missing approved parent call");
        var grant = new HttpDestinationGrant(deadline -> source.fetch(), context);
        return new ApprovedHttpDestination() {
            public void bind(IsolatedAgent.Runtime runtime, String operation) {
                grant.bind(runtime, operation);
            }

            public HttpDocument fetch() {
                return grant.fetch();
            }

            public void publish(IsolatedAgent child) {
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
