package top.focess.veto.builtin.monitor;

import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.tool.AgentTool;
import top.focess.veto.api.agent.tool.ToolCapability;

/** Marker interface for tools backed by {@link MonitorOperations}. */
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
}
