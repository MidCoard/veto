package top.focess.veto.agent.tool;

import java.util.Map;
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.screening.Danger;

/**
 * An in-process JAR plugin tool. It is record-authored and executed through the internal tool state
 * exactly like a {@link NativeToolDefinition} — the host reflects {@link #argsClass()} into the
 * input schema (inherited from {@link LocalToolDefinition}) and invokes the contributing {@link
 * CapabilityTool} bean's {@code execute(args)} on the caller thread. It differs from a native tool
 * only by carrying plugin provenance ({@link PluginSourced}), so it stays session-scoped and
 * revision-pinned like every other plugin contribution.
 *
 * <p>Its capability is the plugin-declared {@link ToolCapability#PRIVILEGED} host-boundary effect:
 * the Gateway gates every call with approval-level danger, and the tool declares no
 * filesystem/command/URL parameters.
 */
public record PluginNativeToolDefinition(
        @NonNull String name,
        @NonNull String bindingId,
        @NonNull String pluginId,
        @NonNull String pluginVersion,
        @NonNull ToolCapability capability,
        @NonNull Danger defaultDanger,
        @NonNull Class<?> toolClass,
        @NonNull Class<?> argsClass,
        @NonNull Map<@NonNull String, @NonNull ParamCategory> paramHints)
        implements LocalToolDefinition, PluginSourced {
    public PluginNativeToolDefinition {
        paramHints = Map.copyOf(paramHints);
    }

    @Override
    public @NonNull String description() {
        return ToolDocs.descriptionOf(toolClass());
    }
}
