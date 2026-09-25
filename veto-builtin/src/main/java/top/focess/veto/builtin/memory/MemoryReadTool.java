package top.focess.veto.builtin.memory;

import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.tool.AgentTool;
import top.focess.veto.api.agent.tool.ToolCapability;

/** Marker interface for tools backed by a {@link MemoryReadCapability}. */
public interface MemoryReadTool<T> extends AgentTool<T> {

    /** Returns the host-supplied read capability. */
    @NonNull MemoryReadCapability memoryReadCapability();

    /** Runs the tool against the supplied capability. */
    @NonNull String execute(@NonNull T args, @NonNull MemoryReadCapability capability);

    @Override
    default @NonNull ToolCapability getCapability() {
        return ToolCapability.PLUGIN_LOCAL;
    }

    @Override
    default @NonNull String execute(@NonNull T args) {
        return execute(args, memoryReadCapability());
    }
}
