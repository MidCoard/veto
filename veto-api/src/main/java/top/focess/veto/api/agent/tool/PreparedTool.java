package top.focess.veto.api.agent.tool;

import org.jspecify.annotations.NonNull;
import top.focess.veto.api.plugin.PluginHost;

/** Pure preparation before Gateway admission. No effectful invocation authority is installed. */
public interface PreparedTool<T> extends NativeTool<T> {
    @NonNull ToolPreparation prepare(@NonNull T args, PluginHost.@NonNull Invocation invocation);
}
