package top.focess.veto.builtin.monitor;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.tool.AgentTool;
import top.focess.veto.api.agent.tool.ToolCapability;
import top.focess.veto.api.agent.tool.ToolErrorCode;
import top.focess.veto.api.agent.tool.ToolErrors;
import top.focess.veto.builtin.BuiltinJson;

/** Shared execution and result encoding for tools backed by {@link MonitorOperations}. */
public abstract class MonitorTool<T> extends AgentTool<T> {
    /** Returns the host-supplied monitor operations. */
    public abstract @NonNull MonitorOperations operations();

    /** Runs the tool against the supplied capability. */
    public abstract @NonNull String execute(@NonNull T args, @NonNull MonitorOperations capability);

    @Override
    public @NonNull ToolCapability getCapability() {
        return ToolCapability.PLUGIN_LOCAL;
    }

    @Override
    public @NonNull String execute(@NonNull T args) {
        return execute(args, operations());
    }

    /** Applies a scoped monitor state transition and renders its result. */
    protected static @NonNull String controlMonitor(
            @NonNull MonitorOperations capability, @NonNull String id, @NonNull String operation) {
        try {
            return encodeResult(capability.control(id, operation));
        } catch (SecurityException error) {
            return ToolErrors.failure(
                    MonitorError.UNKNOWN,
                    "Monitor not found: no accessible monitor has id " + id + ".");
        } catch (IllegalArgumentException error) {
            return ToolErrors.failure(
                    MonitorError.GROUP_MANAGED,
                    "Monitor not updated: group observation monitors follow the group lifecycle and"
                            + " cannot be paused, resumed, or cancelled directly.");
        }
    }

    /** Encodes the monitor result using the existing tool error contract. */
    protected static @NonNull String encodeResult(@NonNull Object value) {
        try {
            return BuiltinJson.encode(value);
        } catch (JsonProcessingException error) {
            return ToolErrors.failure(
                    ToolErrorCode.RESULT.ENCODING_FAILED,
                    "Encoding failed: could not encode the monitor result.");
        }
    }
}
