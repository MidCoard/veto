package top.focess.veto.agent.tool;

import org.jspecify.annotations.NonNull;

/** Common execution and effect contract for every in-process tool. */
public interface CapabilityTool<T> {
    /** The effect boundary required to authorize this tool. */
    @NonNull ToolCapability getCapability();

    @NonNull String getName();

    @NonNull Class<T> getArgsClass();

    @NonNull String execute(@NonNull T args) throws Exception;
}
