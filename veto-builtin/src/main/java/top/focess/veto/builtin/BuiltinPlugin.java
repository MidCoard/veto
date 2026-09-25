package top.focess.veto.builtin;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import top.focess.veto.api.agent.tool.CapabilityTool;
import top.focess.veto.api.agent.tool.ToolDocs;
import top.focess.veto.api.plugin.AbstractVetoPlugin;
import top.focess.veto.api.plugin.PluginContext;
import top.focess.veto.api.plugin.PluginContributions;
import top.focess.veto.api.plugin.PluginHost;
import top.focess.veto.api.plugin.PluginIdentity;
import top.focess.veto.api.plugin.agent.AgentHost;
import top.focess.veto.api.plugin.contract.JsonValue;
import top.focess.veto.api.plugin.contract.StandardContributionPoints;
import top.focess.veto.api.plugin.contribution.Contribution;
import top.focess.veto.api.search.SearchServices;
import top.focess.veto.builtin.group.*;
import top.focess.veto.builtin.memory.MemoryRuntime;
import top.focess.veto.builtin.memory.MemoryTools;
import top.focess.veto.builtin.monitor.MonitorFrontend;
import top.focess.veto.builtin.monitor.MonitorRuntime;
import top.focess.veto.builtin.monitor.MonitorTools;
import top.focess.veto.builtin.planning.PlanConfig;
import top.focess.veto.builtin.planning.SubmitPlanTool;
import top.focess.veto.builtin.process.ProcessRuntime;
import top.focess.veto.builtin.process.TaskEvents;
import top.focess.veto.builtin.process.TasksFrontend;
import top.focess.veto.builtin.questions.QuestionRuntime;
import top.focess.veto.builtin.questions.QuestionsFrontend;
import top.focess.veto.builtin.response.AnswerWithCitationsTool;
import top.focess.veto.builtin.response.CitationResponsePolicy;
import top.focess.veto.builtin.search.BraveSearchProvider;
import top.focess.veto.builtin.search.DuckDuckGoSearchProvider;
import top.focess.veto.builtin.search.SearchServiceClient;
import top.focess.veto.builtin.skills.SkillRuntime;
import top.focess.veto.builtin.tools.*;
import top.focess.veto.builtin.web.*;
import top.focess.veto.builtin.workspace.*;

/** Built-in tool implementations registered through the same API as third-party plugins. */
public final class BuiltinPlugin extends AbstractVetoPlugin {
    private final @NonNull ReadGitHubRepositoryTool github = new ReadGitHubRepositoryTool();
    private final @NonNull DuckDuckGoSearchProvider duckduckgo = new DuckDuckGoSearchProvider();
    private @Nullable BraveSearchProvider brave;
    private @Nullable MonitorRuntime monitors;
    private @Nullable GroupRuntime groups;
    private @Nullable PluginHost host;
    private @Nullable QuestionRuntime questions;
    private @Nullable ProcessRuntime processes;
    private @Nullable TaskEvents taskEvents;
    private @Nullable SkillRuntime skills;

    public @NonNull SkillRuntime skillRuntime() {
        if (skills == null) throw new IllegalStateException("Builtin not initialized");
        return skills;
    }

    public @NonNull GroupRuntime groupRuntime() {
        var runtime = groups;
        if (runtime == null) throw new IllegalStateException("Builtin is not initialized");
        return runtime;
    }

    public @NonNull MonitorRuntime monitorRuntime() {
        var runtime = monitors;
        if (runtime == null) throw new IllegalStateException("Builtin is not initialized");
        return runtime;
    }

    @Override
    public @NonNull PluginIdentity identity() {
        return new PluginIdentity("top.focess.builtin", "1.0.100");
    }

    @Override
    protected @NonNull PluginContributions onInitialize(
            @NonNull PluginContext context, JsonValue.@NonNull ObjectValue configuration) {
        host = context.service(ToolDocs.nonNullClass(PluginHost.class)).orElse(null);
        var questionRuntime = new QuestionRuntime(host);
        questions = questionRuntime;
        var groupRuntime = new GroupRuntime(context, configuration);
        groups = groupRuntime;
        groupRuntime.awaitHostReady();
        var groupOperations = groupRuntime.operations();
        var monitorRuntime = new MonitorRuntime(context, groupRuntime);
        monitors = monitorRuntime;
        var operations = monitorRuntime.operations();
        var processRuntime = new ProcessRuntime(context);
        processes = processRuntime;
        if (host != null) {
            var events = new TaskEvents(host, monitorRuntime.service());
            taskEvents = events;
            processRuntime.events(events);
        }
        var memoryRuntime = new MemoryRuntime(context, configuration);
        var skillRuntime = new SkillRuntime(context, configuration);
        skills = skillRuntime;
        List<CapabilityTool<?>> tools =
                List.of(
                        new GroupTools.CreateGroup(groupRuntime.delegation()),
                        new RunCommandTool(processRuntime.execution("run_command")),
                        new RunTaskTool(processRuntime.execution("run_task")),
                        new ViewTaskTool(processRuntime.control("view_task")),
                        new InputTaskTool(processRuntime.control("input_task")),
                        new StopTaskTool(processRuntime.control("stop_task")),
                        new LoadSkillTool(skillRuntime),
                        new AskUserTool(questionRuntime),
                        github,
                        new WebSearchTool(new SearchServiceClient(context, configuration)),
                        new WebFetchTool(
                                new WebReader(
                                        () ->
                                                context.service(
                                                                ToolDocs.nonNullClass(
                                                                        AgentHost.class))
                                                        .orElseThrow(
                                                                () ->
                                                                        new IllegalStateException(
                                                                                "Isolated execution unavailable")),
                                        new ReaderConfig(configuration.values()))),
                        new GroupTools.DisbandGroup(groupOperations),
                        new GroupTools.InspectGroup(groupOperations),
                        new GroupTools.PostMessage(groupOperations),
                        new DagTools.RemoveNode(groupOperations),
                        new CollaborationTools.CreateMate(groupOperations),
                        new CollaborationTools.CreateTask(
                                groupOperations, monitorRuntime::awaitGroup),
                        new CollaborationTools.CancelTask(groupOperations),
                        new CollaborationTools.RemoveMate(groupOperations),
                        new MemoryTools.RecallMemory(memoryRuntime.reader()),
                        new MemoryTools.WriteMemory(memoryRuntime.writer("write_memory")),
                        new MemoryTools.ForgetMemory(memoryRuntime.writer("forget_memory")),
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
        var key = configuration.values().get("brave-api-key");
        var configuredBrave =
                new BraveSearchProvider(
                        key instanceof JsonValue.StringValue value ? value.value() : "");
        brave = configuredBrave;
        List<Contribution<?>> contributions = new ArrayList<>();
        contributions.add(
                Contribution.of(
                        StandardContributionPoints.DATA_LIFECYCLE, "memory-data", memoryRuntime));
        for (var tool : tools) {
            contributions.add(
                    Contribution.of(StandardContributionPoints.NATIVE_TOOLS, tool.getName(), tool));
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
                        SearchServices.registration(configuredBrave)));
        contributions.add(
                Contribution.of(
                        StandardContributionPoints.AGENT_WORK,
                        "monitor-work",
                        monitorRuntime.work()));
        contributions.add(
                Contribution.of(
                        StandardContributionPoints.SESSION_LIFECYCLE,
                        "monitor-lifecycle",
                        monitorRuntime.service()));
        contributions.add(
                Contribution.of(
                        StandardContributionPoints.FRONTEND,
                        "monitors",
                        new MonitorFrontend(monitorRuntime.service()).contribution()));
        contributions.add(
                Contribution.of(
                        StandardContributionPoints.AGENT_CONFIGURATION,
                        "group-configuration",
                        groupRuntime));
        contributions.add(
                Contribution.of(
                        StandardContributionPoints.SESSION_LIFECYCLE,
                        "group-lifecycle",
                        groupRuntime));
        contributions.add(
                Contribution.of(
                        StandardContributionPoints.FRONTEND,
                        "groups",
                        new GroupFrontend(groupRuntime).contribution()));
        contributions.add(
                Contribution.of(
                        StandardContributionPoints.SESSION_LIFECYCLE,
                        "questions-lifecycle",
                        questionRuntime));
        contributions.add(
                Contribution.of(
                        StandardContributionPoints.FRONTEND,
                        "questions",
                        new QuestionsFrontend(questionRuntime).contribution()));
        contributions.add(
                Contribution.of(
                        StandardContributionPoints.FRONTEND,
                        "tools",
                        ToolsFrontend.contribution()));
        contributions.add(
                Contribution.of(
                        StandardContributionPoints.SESSION_LIFECYCLE,
                        "tasks-lifecycle",
                        processRuntime));
        contributions.add(
                Contribution.of(
                        StandardContributionPoints.FRONTEND,
                        "tasks",
                        new TasksFrontend(processRuntime.tasks()).contribution()));
        return new PluginContributions(contributions);
    }

    @Override
    protected void onStart() {
        Runnable ready =
                () -> {
                    if (groups != null) groups.start();
                    if (monitors != null) monitors.start();
                    if (taskEvents != null) taskEvents.start();
                };
        if (host == null) ready.run();
        else host.whenReady(ready);
    }

    @Override
    protected void onStopping() {
        try {
            if (questions != null) questions.close();
        } finally {
            if (processes != null) processes.tasks().close();
        }
    }

    @Override
    protected void onClose() {
        try {
            if (processes != null) processes.tasks().close();
        } finally {
            closeServices();
        }
    }

    private void closeServices() {
        if (skills != null) skills.close();
        if (taskEvents != null) taskEvents.close();
        if (questions != null) questions.close();
        if (monitors != null) monitors.close();
        if (groups != null) groups.close();
        github.close();
        duckduckgo.close();
        if (brave != null) brave.close();
    }
}
