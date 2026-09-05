package top.focess.veto.agent.loop;

import java.util.Set;
import org.jspecify.annotations.NonNull;
import top.focess.veto.llm.core.VetoResponse;
import top.focess.veto.llm.exceptions.ModelSchemaException;

/** Runtime enforcement of the response contract, independent of provider schema support. */
public final class ResponseEnforcer {
    private ResponseEnforcer() {}

    public static @NonNull VetoResponse enforce(
            @NonNull VetoResponse response, boolean guidedEnabled) {
        return enforce(response, guidedEnabled, Set.of());
    }

    public static @NonNull VetoResponse enforce(
            @NonNull VetoResponse response,
            boolean guidedEnabled,
            @NonNull Set<@NonNull String> allowedToolNames) {
        var calls = response.calls();
        var guide = response.guide();
        if (guide != null) {
            if (!guidedEnabled)
                throw new ModelSchemaException("guide is disabled for this session");
            if (calls != null)
                throw new ModelSchemaException("calls and guide are mutually exclusive");
            var actions = guide.actions();
            if (!actions.isArray() || actions.isEmpty())
                throw new ModelSchemaException("guide.actions must be a non-empty array");
        }
        if (calls != null) {
            if (calls.isEmpty())
                throw new ModelSchemaException("calls must be non-empty when present");
            for (var call : calls) {
                if (!allowedToolNames.isEmpty() && !allowedToolNames.contains(call.toolName())) {
                    throw new ModelSchemaException(
                            "calls[].tool_name must exactly name a catalog tool; '"
                                    + call.toolName()
                                    + "' is not in this turn's tool catalog");
                }
            }
        }
        String message = response.message();
        if (calls == null && guide == null && (message == null || message.isBlank()))
            throw new ModelSchemaException("message required (no tool calls or guide to execute)");
        return response;
    }
}
