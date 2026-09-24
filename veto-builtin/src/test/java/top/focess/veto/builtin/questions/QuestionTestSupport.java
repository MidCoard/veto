package top.focess.veto.builtin.questions;

import org.jspecify.annotations.NonNull;
import top.focess.veto.api.plugin.PluginHost;
import top.focess.veto.api.plugin.contract.FrontendContribution.Scope;

final class QuestionTestSupport {
    static @NonNull Scope scope(@NonNull String agent) {
        return new Scope("owner", "session", agent);
    }

    static PluginHost.@NonNull Invocation invocation(@NonNull String agent, @NonNull String call) {
        return new PluginHost.Invocation("owner", "session", agent, "request", call);
    }

    static @NonNull PluginHost host() {
        return new PluginHost() {
            public @NonNull Invocation invocation(@NonNull String tool) {
                return QuestionTestSupport.invocation("test-agent", "test-call");
            }

            public void wake(
                    @NonNull String owner, @NonNull String session, @NonNull String agent) {}

            public void invalidate(@NonNull String session, @NonNull String resource) {}
        };
    }
}
