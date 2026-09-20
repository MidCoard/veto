package top.focess.veto.extension.contract;

import org.jspecify.annotations.NonNull;

/** Only invoked by a host adapter after schema validation and authorization. */
@FunctionalInterface
public interface ToolHandler {
    @NonNull JsonValue invoke(
            JsonValue.@NonNull ObjectValue arguments, @NonNull Cancellation invocation)
            throws ExtensionFailure;
}
