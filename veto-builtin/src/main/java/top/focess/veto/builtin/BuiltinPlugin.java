package top.focess.veto.builtin;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import top.focess.veto.api.agent.tool.CapabilityTool;
import top.focess.veto.api.plugin.AbstractVetoPlugin;
import top.focess.veto.api.plugin.PluginContext;
import top.focess.veto.api.plugin.PluginContributions;
import top.focess.veto.api.plugin.PluginIdentity;
import top.focess.veto.api.plugin.agent.AgentHost;
import top.focess.veto.api.plugin.contract.JsonValue;
import top.focess.veto.api.plugin.contract.StandardContributionPoints;
import top.focess.veto.api.plugin.contribution.Contribution;
import top.focess.veto.builtin.search.SearchServices;
import top.focess.veto.builtin.group.*;
import top.focess.veto.builtin.memory.MemoryTools;
import top.focess.veto.builtin.monitor.MonitorFrontend;
import top.focess.veto.builtin.monitor.MonitorTools;
import top.focess.veto.builtin.planning.PlanConfig;
import top.focess.veto.builtin.planning.SubmitPlanTool;
import top.focess.veto.builtin.process.TasksFrontend;
import top.focess.veto.builtin.questions.QuestionsFrontend;
import top.focess.veto.builtin.response.AnswerWithCitationsTool;
import top.focess.veto.builtin.response.CitationResponsePolicy;
import top.focess.veto.builtin.search.DuckDuckGoSearchProvider;
import top.focess.veto.builtin.search.SearchServiceClient;
import top.focess.veto.builtin.tools.*;
import top.focess.veto.builtin.web.*;
import top.focess.veto.builtin.workspace.*;

import java.util.ArrayList;
import java.util.List;

/** Built-in tool implementations registered through the same API as third-party plugins. */
public final class BuiltinPlugin extends AbstractVetoPlugin {
    private final @NonNull ReadGitHubRepositoryTool github = new ReadGitHubRepositoryTool();
    private final @NonNull DuckDuckGoSearchProvider duckduckgo = new DuckDuckGoSearchProvider();
    private @Nullable BuiltinComponents components;

    private @NonNull BuiltinComponents initialized() {
        BuiltinComponents current = components;
        if (current == null) throw new IllegalStateException("Builtin is not initialized");
        return current;
    }

    @Override
    public @NonNull PluginIdentity identity() {
        return new PluginIdentity("top.focess.builtin", "1.0.100");
    }

    @Override
    public @NonNull String displayName() {
        return "Veto Built-in";
    }

    @Override
    public @NonNull String preferredToolName(@NonNull String localId) {
        return localId;
    }

    @Override
    protected @NonNull PluginContributions onInitialize(
            @NonNull PluginContext context, JsonValue.@NonNull ObjectValue configuration) {
        components = new BuiltinComponents(context, configuration);
        var runtime = initialized();
        var groupOperations = runtime.groups.operations();
        var operations = runtime.monitors.operations();
        List<CapabilityTool<?>> tools =
                List.of(
                        new GroupTools.CreateGroup(runtime.groups.delegation()),
                        new RunCommandTool(runtime.processes.execution("run_command")),
                        new RunTaskTool(runtime.processes.execution("run_task")),
                        new ViewTaskTool(runtime.processes.control("view_task")),
                        new InputTaskTool(runtime.processes.control("input_task")),
                        new StopTaskTool(runtime.processes.control("stop_task")),
                        new LoadSkillTool(runtime.skills),
                        new AskUserTool(runtime.questions),
                        github,
                        new WebSearchTool(new SearchServiceClient(context, configuration)),
                        new WebFetchTool(
                                new WebReader(
                                        () ->
                                                context.service(
                                                                AgentHost.class)
                                                        .orElseThrow(
                                                                () ->
                                                                        new IllegalStateException(
                                                                                "Isolated execution"
                                                                                    + " unavailable")),
                                        new ReaderConfig(configuration.values()))),
                        new GroupTools.DisbandGroup(groupOperations),
                        new GroupTools.InspectGroup(groupOperations),
                        new GroupTools.PostMessage(groupOperations),
                        new DagTools.RemoveNode(groupOperations),
                        new CollaborationTools.CreateMate(groupOperations),
                        new CollaborationTools.CreateTask(
                                groupOperations, runtime.monitors::awaitGroup),
                        new CollaborationTools.CancelTask(groupOperations),
                        new CollaborationTools.RemoveMate(groupOperations),
                        new MemoryTools.RecallMemory(runtime.memory.reader()),
                        new MemoryTools.WriteMemory(runtime.memory.writer("write_memory")),
                        new MemoryTools.ForgetMemory(runtime.memory.writer("forget_memory")),
                        new MonitorTools.CreateMonitor(operations),
                        new MonitorTools.InspectMonitor(operations),
                        new MonitorTools.PauseMonitor(operations),
                        new MonitorTools.ResumeMonitor(operations),
                        new MonitorTools.CancelMonitor(operations),
                        new SubmitPlanTool(PlanConfig.from(configuration)),
                        new AnswerWithCitationsTool(),
                        new ViewFileTool(),
                        new ListDirTool(),
                        new FindFilesTool(),
                        new GrepSearchTool(),
                        new WriteToFileTool(),
                        new ReplaceFileContentTool(),
                        new MovePathTool(),
                        new DeletePathTool());
        List<Contribution<?>> contributions = new ArrayList<>();
        contributions.add(
                Contribution.of(
                        StandardContributionPoints.DATA_LIFECYCLE, "memory-data", runtime.memory));
        for (var tool : tools) {
            contributions.add(
                    Contribution.of(StandardContributionPoints.TOOLS, tool.getName(), tool));
        }
        contributions.add(
                Contribution.of(
                        StandardContributionPoints.MODEL_RESPONSE,
                        "responses",
                        new CitationResponsePolicy()));
        contributions.add(
                Contribution.of(
                        StandardContributionPoints.SERVICES,
                        "duckduckgo",
                        SearchServices.registration(duckduckgo)));
        contributions.add(
                Contribution.of(
                        StandardContributionPoints.SERVICES,
                        "brave",
                        SearchServices.registration(runtime.brave)));
        contributions.add(
                Contribution.of(
                        StandardContributionPoints.AGENT_INBOX,
                        "monitor-work",
                        runtime.monitors.work()));
        contributions.add(
                Contribution.of(
                        StandardContributionPoints.LISTENERS,
                        "monitor-lifecycle",
                        runtime.monitors.service()));
        contributions.add(
                Contribution.of(
                        StandardContributionPoints.FRONTEND,
                        "monitors",
                        new MonitorFrontend(runtime.monitors.service()).contribution()));
        contributions.add(
                Contribution.of(
                        StandardContributionPoints.AGENT_CONFIGURATION,
                        "group-configuration",
                        runtime.groups));
        contributions.add(
                Contribution.of(
                        StandardContributionPoints.LISTENERS, "group-lifecycle", runtime.groups));
        contributions.add(
                Contribution.of(
                        StandardContributionPoints.FRONTEND,
                        "groups",
                        new GroupFrontend(runtime.groups).contribution()));
        contributions.add(
                Contribution.of(
                        StandardContributionPoints.LISTENERS,
                        "questions-lifecycle",
                        runtime.questions));
        contributions.add(
                Contribution.of(
                        StandardContributionPoints.FRONTEND,
                        "questions",
                        new QuestionsFrontend(runtime.questions).contribution()));
        contributions.add(
                Contribution.of(
                        StandardContributionPoints.FRONTEND,
                        "tools",
                        ToolsFrontend.contribution()));
        contributions.add(
                Contribution.of(
                        StandardContributionPoints.LISTENERS,
                        "tasks-lifecycle",
                        runtime.processes));
        contributions.add(
                Contribution.of(
                        StandardContributionPoints.FRONTEND,
                        "tasks",
                        new TasksFrontend(runtime.processes.tasks()).contribution()));
        return new PluginContributions(contributions);
    }

    @Override
    protected void onStart() {
        var runtime = initialized();
        runtime.host.whenReady(
                () -> {
                    runtime.groups.start();
                    runtime.monitors.start();
                    runtime.taskEvents.start();
                });
    }

    @Override
    protected void onStopping() {
        BuiltinComponents current = components;
        if (current != null) current.stopping();
    }

    @Override
    protected void onClose() {
        try {
            BuiltinComponents current = components;
            components = null;
            if (current != null) current.close();
        } finally {
            github.close();
            duckduckgo.close();
        }
    }
}
