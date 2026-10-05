package top.focess.veto.integration.plugins;

import java.util.UUID;
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.tool.ToolCallContextHolder;
import top.focess.veto.api.plugin.PluginHost;
import top.focess.veto.api.plugin.Scope;

/** Test host for runner wait assertions; production authorization is exercised separately. */
public final class QuestionTestSupport {
    private QuestionTestSupport() {}

    public static Scope.@NonNull AgentScope scope(@NonNull String agent) {
        return new Scope.AgentScope(
                UUID.fromString("36fc510c-70b8-5be2-b3cc-c9d1bc0c6376"), "session", agent);
    }

    public static @NonNull PluginHost host() {
        return new PluginHost() {
            public @NonNull Invocation invocation(@NonNull String tool) {
                var context = ToolCallContextHolder.get();
                if (context == null) throw new AssertionError("Missing tool context");
                return new Invocation(
                        UUID.fromString("36fc510c-70b8-5be2-b3cc-c9d1bc0c6376"),
                        "session",
                        context.agentId(),
                        context.requestId(),
                        context.executionPermit().callId());
            }

            public void wake(
                    @NonNull UUID userId, @NonNull String session, @NonNull String agent) {}

            public void invalidate(@NonNull String session, @NonNull String resource) {}
        };
    }
}
