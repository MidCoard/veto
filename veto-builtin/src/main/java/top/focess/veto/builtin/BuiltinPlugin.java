package top.focess.veto.builtin;

import java.util.List;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.tool.CapabilityTool;
import top.focess.veto.api.plugin.AbstractVetoPlugin;
import top.focess.veto.api.plugin.PluginContext;
import top.focess.veto.api.plugin.PluginContributions;
import top.focess.veto.api.plugin.PluginIdentity;
import top.focess.veto.api.plugin.contract.JsonValue;
import top.focess.veto.api.plugin.contract.StandardContributionPoints;
import top.focess.veto.api.plugin.contribution.Contribution;
import top.focess.veto.builtin.agent.CreateGroup;
import top.focess.veto.builtin.workspace.*;

/** Built-in tool implementations registered through the same API as third-party plugins. */
public final class BuiltinPlugin extends AbstractVetoPlugin {
    @Override
    public @NonNull PluginIdentity identity() {
        return new PluginIdentity("top.focess.builtin", "1.0.100");
    }

    @Override
    protected @NonNull PluginContributions onInitialize(
            @NonNull PluginContext context, JsonValue.@NonNull ObjectValue configuration) {
        List<CapabilityTool<?>> tools =
                List.of(
                        new CreateGroup(),
                        new ViewFileTool(),
                        new ListDirTool(),
                        new FindFilesTool(),
                        new GrepSearchTool(),
                        new WriteToFileTool(),
                        new ReplaceFileContentTool(),
                        new MovePathTool(),
                        new DeletePathTool());
        return new PluginContributions(
                tools.stream()
                        .<Contribution<?>>map(
                                tool ->
                                        Contribution.of(
                                                StandardContributionPoints.NATIVE_TOOLS,
                                                tool.getName(),
                                                tool))
                        .toList());
    }

    @Override
    protected void onStart() {}

    @Override
    protected void onClose() {}
}
