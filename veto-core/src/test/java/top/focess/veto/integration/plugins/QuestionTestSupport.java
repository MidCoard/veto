package top.focess.veto.integration.plugins;

import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.tool.ToolCallContextHolder;
import top.focess.veto.api.plugin.PluginHost;
import top.focess.veto.api.plugin.contract.FrontendContribution.Scope;

/** Test host for runner wait assertions; production authorization is exercised separately. */
public final class QuestionTestSupport {
    private QuestionTestSupport() {}

    public static @NonNull Scope scope(@NonNull String agent) {
        return new Scope("owner", "session", agent);
    }

    public static @NonNull PluginHost host() {
        return new PluginHost() {
            public @NonNull Invocation invocation(@NonNull String tool) {
                var context = ToolCallContextHolder.get();
                if (context == null) throw new AssertionError("Missing tool context");
                return new Invocation(
                        "owner",
                        "session",
                        context.agentId(),
                        context.requestId(),
                        context.executionPermit().callId());
            }

            public void wake(
                    @NonNull String owner, @NonNull String session, @NonNull String agent) {}

            public void invalidate(@NonNull String session, @NonNull String resource) {}
        };
    }
}
