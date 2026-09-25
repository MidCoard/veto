package top.focess.veto.api.agent.tool;

import org.jspecify.annotations.NonNull;
import top.focess.veto.api.plugin.PluginHost;

/**
 * Pure preparation before Gateway admission. No effectful invocation authority is installed.
 *
 * @param <T> immutable argument value decoded by the host
 */
public interface PreparedTool<T> extends NativeTool<T> {
    /**
     * Describes the exact effect to screen before execution.
     *
     * <p>This callback must only inspect and normalize intent. The supplied invocation identifies
     * the current plugin/session call but does not itself authorize the requested effect.
     *
     * @param args decoded call arguments
     * @param invocation identity and context of the current invocation
     * @return normalized intent and screening facts
     */
    @NonNull ToolPreparation prepare(@NonNull T args, PluginHost.@NonNull Invocation invocation);
}
