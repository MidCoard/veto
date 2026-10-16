package top.focess.veto.builtin.questions;

import java.util.UUID;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.plugin.PluginHost;
import top.focess.veto.api.plugin.Scope;
import top.focess.veto.api.plugin.contract.JsonValue;

final class QuestionTestSupport {
    static Scope.@NonNull AgentScope scope(@NonNull String agent) {
        return new Scope.AgentScope(
                UUID.fromString("36fc510c-70b8-5be2-b3cc-c9d1bc0c6376"), "session", agent);
    }

    static PluginHost.@NonNull Invocation invocation(@NonNull String agent, @NonNull String call) {
        return new PluginHost.Invocation(
                UUID.fromString("36fc510c-70b8-5be2-b3cc-c9d1bc0c6376"),
                "session",
                agent,
                "request",
                call);
    }

    static @NonNull PluginHost host() {
        return new PluginHost() {
            public @NonNull Invocation invocation(@NonNull String tool) {
                return QuestionTestSupport.invocation("test-agent", "test-call");
            }

            public void wake(
                    @NonNull UUID userId, @NonNull String session, @NonNull String agent) {}

            public void publish(
                    Scope.@NonNull SessionScope scope,
                    @NonNull String topic,
                    JsonValue.@NonNull ObjectValue facts) {}
        };
    }
}
