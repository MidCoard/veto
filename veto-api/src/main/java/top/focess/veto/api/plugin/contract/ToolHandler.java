package top.focess.veto.api.plugin.contract;

import org.jspecify.annotations.NonNull;

/** Only invoked by a host adapter after schema validation and authorization. */
@FunctionalInterface
public interface ToolHandler {
    /**
     * Executes one authorized call with validated arguments and cooperative cancellation.
     *
     * @param arguments validated immutable arguments
     * @param invocation cooperative cancellation signal
     * @return bounded JSON result
     * @throws PluginFailure when execution fails with a public plugin error
     */
    @NonNull JsonValue invoke(
            JsonValue.@NonNull ObjectValue arguments, @NonNull Cancellation invocation)
            throws PluginFailure;
}
