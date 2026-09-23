package top.focess.veto.builtin;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import top.focess.veto.api.agent.tool.CapabilityTool;
import top.focess.veto.api.plugin.AbstractVetoPlugin;
import top.focess.veto.api.plugin.PluginContext;
import top.focess.veto.api.plugin.PluginContributions;
import top.focess.veto.api.plugin.PluginIdentity;
import top.focess.veto.api.plugin.contract.JsonValue;
import top.focess.veto.api.plugin.contract.StandardContributionPoints;
import top.focess.veto.api.plugin.contribution.Contribution;
import top.focess.veto.builtin.agent.CreateGroup;
import top.focess.veto.builtin.group.*;
import top.focess.veto.builtin.memory.MemoryTools;
import top.focess.veto.builtin.monitor.MonitorTools;
import top.focess.veto.builtin.planning.AnswerWithCitationsTool;
import top.focess.veto.builtin.planning.SubmitPlanTool;
import top.focess.veto.builtin.search.BraveSearchProvider;
import top.focess.veto.builtin.search.DuckDuckGoSearchProvider;
import top.focess.veto.builtin.tools.*;
import top.focess.veto.builtin.web.*;
import top.focess.veto.builtin.workspace.*;

/** Built-in tool implementations registered through the same API as third-party plugins. */
public final class BuiltinPlugin extends AbstractVetoPlugin {
    private final @NonNull DuckDuckGoSearchProvider duckduckgo = new DuckDuckGoSearchProvider();
    private @Nullable BraveSearchProvider brave;

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
                        new RunCommandTool(),
                        new RunTaskTool(),
                        new ViewTaskTool(),
                        new InputTaskTool(),
                        new StopTaskTool(),
                        new LoadSkillTool(),
                        new AskUserTool(),
                        new ReadGitHubRepositoryTool(),
                        new WebSearchTool(),
                        new WebFetchTool(),
                        new GroupTools.DisbandGroup(),
                        new GroupTools.InspectGroup(),
                        new GroupTools.PostMessage(),
                        new DagTools.RemoveNode(),
                        new CollaborationTools.CreateMate(),
                        new CollaborationTools.CreateTask(),
                        new CollaborationTools.CancelTask(),
                        new CollaborationTools.RemoveMate(),
                        new MemoryTools.RecallMemory(),
                        new MemoryTools.WriteMemory(),
                        new MemoryTools.ForgetMemory(),
                        new MonitorTools.CreateMonitor(),
                        new MonitorTools.InspectMonitor(),
                        new MonitorTools.PauseMonitor(),
                        new MonitorTools.ResumeMonitor(),
                        new MonitorTools.CancelMonitor(),
                        new SubmitPlanTool(),
                        new AnswerWithCitationsTool(),
                        new ViewFileTool(),
                        new ListDirTool(),
                        new FindFilesTool(),
                        new GrepSearchTool(),
                        new WriteToFileTool(),
                        new ReplaceFileContentTool(),
                        new MovePathTool(),
                        new DeletePathTool());
        var key = configuration.values().get("brave-api-key");
        var configuredBrave =
                new BraveSearchProvider(
                        key instanceof JsonValue.StringValue value ? value.value() : "");
        brave = configuredBrave;
        List<Contribution<?>> contributions = new ArrayList<>();
        for (var tool : tools) {
            contributions.add(
                    Contribution.of(StandardContributionPoints.NATIVE_TOOLS, tool.getName(), tool));
        }
        contributions.add(
                Contribution.of(
                        StandardContributionPoints.SEARCH_PROVIDERS, "duckduckgo", duckduckgo));
        contributions.add(
                Contribution.of(
                        StandardContributionPoints.SEARCH_PROVIDERS, "brave", configuredBrave));
        return new PluginContributions(contributions);
    }

    @Override
    protected void onStart() {}

    @Override
    protected void onClose() {
        duckduckgo.close();
        if (brave != null) brave.close();
    }
}
