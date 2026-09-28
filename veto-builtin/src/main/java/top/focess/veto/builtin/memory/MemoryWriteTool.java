package top.focess.veto.builtin.memory;

import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.tool.AgentTool;
import top.focess.veto.api.agent.tool.ToolCapability;

/** Marker interface for tools backed by a {@link MemoryWriteCapability}. */
public abstract class MemoryWriteTool<T> extends AgentTool<T> {

    /** Returns the host-supplied write capability. */
    public abstract @NonNull MemoryWriteCapability memoryWriteCapability();

    /** Runs the tool against the supplied capability. */
    public abstract @NonNull String execute(
            @NonNull T args, @NonNull MemoryWriteCapability capability);

    @Override
    public @NonNull ToolCapability getCapability() {
        return ToolCapability.PLUGIN_LOCAL;
    }

    @Override
    public @NonNull String execute(@NonNull T args) {
        return execute(args, memoryWriteCapability());
    }
}
