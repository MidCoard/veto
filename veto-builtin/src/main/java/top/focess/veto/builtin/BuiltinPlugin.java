package top.focess.veto.builtin;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.tool.CapabilityTool;
import top.focess.veto.api.agent.tool.Tool;
import top.focess.veto.api.plugin.PluginContext;
import top.focess.veto.api.plugin.PluginContributions;
import top.focess.veto.api.plugin.PluginHost;
import top.focess.veto.api.plugin.PluginIdentity;
import top.focess.veto.api.plugin.VetoPlugin;
import top.focess.veto.api.plugin.agent.AgentHost;
import top.focess.veto.api.plugin.contract.JsonValue;
import top.focess.veto.api.plugin.contract.StandardContributionPoints;
import top.focess.veto.api.plugin.contribution.Contribution;
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
import top.focess.veto.builtin.search.SearchHub;
import top.focess.veto.builtin.search.SearchServiceClient;
import top.focess.veto.builtin.skills.SkillRuntime;
import top.focess.veto.builtin.tools.*;
import top.focess.veto.builtin.web.*;
import top.focess.veto.builtin.workspace.*;

/** Built-in tool implementations registered through the same API as third-party plugins. */
public final class BuiltinPlugin extends VetoPlugin {
    private final @NonNull ReadGitHubRepositoryTool github;
    private final @NonNull DuckDuckGoSearchProvider duckduckgo;
    private final @NonNull PluginHost host;
    private final @NonNull QuestionRuntime questions;
    private final @NonNull GroupRuntime groups;
    private final @NonNull MonitorRuntime monitors;
    private final @NonNull ProcessRuntime processes;
    private final @NonNull TaskEvents taskEvents;
    private final @NonNull MemoryRuntime memory;
    private final @NonNull SkillRuntime skills;
    private final @NonNull BraveSearchProvider brave;
    private final @NonNull PluginContributions contributions;

    /** Constructs all builtin-owned features using the host-bound context. */
    public BuiltinPlugin(
            @NonNull PluginContext context, JsonValue.@NonNull ObjectValue configuration) {
        List<Runnable> cleanup = new ArrayList<>();
        try {
            host = context.host();
            github = new ReadGitHubRepositoryTool();
            cleanup.add(github::close);
            duckduckgo = new DuckDuckGoSearchProvider();
            cleanup.add(duckduckgo::close);
            questions = new QuestionRuntime(host);
            cleanup.add(questions::close);
            groups = new GroupRuntime(context, configuration);
            cleanup.add(groups::close);
            groups.awaitHostReady();
            monitors = new MonitorRuntime(context, groups);
            cleanup.add(monitors::close);
            taskEvents = new TaskEvents(host, monitors.service());
            cleanup.add(taskEvents::close);
            processes = new ProcessRuntime(context, taskEvents);
            cleanup.add(() -> processes.tasks().close());
            memory = new MemoryRuntime(context, configuration);
            skills = new SkillRuntime(context, configuration);
            cleanup.add(skills::close);
            var key = configuration.values().get("brave-api-key");
            brave =
                    new BraveSearchProvider(
                            key instanceof JsonValue.StringValue value ? value.value() : "");
            cleanup.add(brave::close);
            var groupOperations = groups.operations();
            var operations = monitors.operations();
            List<Tool> tools =
                    List.of(
                            new GroupTools.CreateGroup(groups.delegation()),
                            new RunCommandTool(processes.execution("run_command")),
                            new RunTaskTool(processes.execution("run_task")),
                            new ViewTaskTool(processes.control("view_task")),
                            new InputTaskTool(processes.control("input_task")),
                            new StopTaskTool(processes.control("stop_task")),
                            new LoadSkillTool(skills),
                            new AskUserTool(questions),
                            github,
                            new WebSearchTool(new SearchServiceClient(context, configuration)),
                            new WebFetchTool(
                                    new WebReader(
                                            () ->
                                                    context.service(AgentHost.class)
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
                                    groupOperations, monitors::awaitGroup),
                            new CollaborationTools.CancelTask(groupOperations),
                            new CollaborationTools.RemoveMate(groupOperations),
                            new MemoryTools.RecallMemory(memory.reader()),
                            new MemoryTools.WriteMemory(memory.writer("write_memory")),
                            new MemoryTools.ForgetMemory(memory.writer("forget_memory")),
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
            List<Contribution<?>> entries = new ArrayList<>();
            entries.add(
                    Contribution.of(
                            StandardContributionPoints.DATA_LIFECYCLE, "memory-data", memory));
            for (var tool : tools) {
                if (tool instanceof CapabilityTool<?> local)
                    entries.add(
                            Contribution.of(
                                    StandardContributionPoints.TOOLS, local.getName(), tool));
            }
            entries.add(
                    Contribution.of(
                            StandardContributionPoints.MODEL_RESPONSE,
                            "responses",
                            new CitationResponsePolicy()));
            entries.add(
                    Contribution.of(
                            StandardContributionPoints.SERVICES,
                            "search",
                            new SearchHub(context.services(), List.of(duckduckgo, brave))));
            entries.add(
                    Contribution.of(
                            StandardContributionPoints.AGENT_INBOX,
                            "monitor-work",
                            monitors.work()));
            entries.add(
                    Contribution.of(
                            StandardContributionPoints.LISTENERS,
                            "monitor-lifecycle",
                            monitors.listener()));
            entries.add(
                    Contribution.of(
                            StandardContributionPoints.FRONTEND,
                            "monitors",
                            new MonitorFrontend(monitors.service())));
            entries.add(
                    Contribution.of(
                            StandardContributionPoints.AGENT_CONFIGURATION,
                            "group-configuration",
                            groups));
            entries.add(
                    Contribution.of(
                            StandardContributionPoints.LISTENERS,
                            "group-lifecycle",
                            groups.listener()));
            entries.add(
                    Contribution.of(
                            StandardContributionPoints.FRONTEND,
                            "groups",
                            new GroupFrontend(groups)));
            entries.add(
                    Contribution.of(
                            StandardContributionPoints.LISTENERS,
                            "questions-lifecycle",
                            questions));
            entries.add(
                    Contribution.of(
                            StandardContributionPoints.FRONTEND,
                            "questions",
                            new QuestionsFrontend(questions)));
            entries.add(
                    Contribution.of(
                            StandardContributionPoints.FRONTEND, "tools", new ToolsFrontend()));
            entries.add(
                    Contribution.of(
                            StandardContributionPoints.LISTENERS, "tasks-lifecycle", processes));
            entries.add(
                    Contribution.of(
                            StandardContributionPoints.FRONTEND,
                            "tasks",
                            new TasksFrontend(processes.tasks())));
            contributions = new PluginContributions(entries);
        } catch (RuntimeException failure) {
            try {
                closeAll(cleanup.reversed());
            } catch (RuntimeException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
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
    public @NonNull PluginContributions contributions() {
        return contributions;
    }

    @Override
    public void start() {
        host.whenReady(
                () -> {
                    groups.start();
                    monitors.start();
                    taskEvents.start();
                });
    }

    @Override
    public void stopping() {
        RuntimeException failure = closeOne(null, questions::close);
        failure = closeOne(failure, () -> processes.tasks().close());
        if (failure != null) throw failure;
    }

    @Override
    public void close() {
        RuntimeException failure = null;
        failure = closeOne(failure, () -> processes.tasks().close());
        failure = closeOne(failure, skills::close);
        failure = closeOne(failure, taskEvents::close);
        failure = closeOne(failure, questions::close);
        failure = closeOne(failure, monitors::close);
        failure = closeOne(failure, groups::close);
        failure = closeOne(failure, brave::close);
        failure = closeOne(failure, github::close);
        failure = closeOne(failure, duckduckgo::close);
        if (failure != null) throw failure;
    }

    private static RuntimeException closeOne(RuntimeException failure, @NonNull Runnable closer) {
        try {
            closer.run();
        } catch (RuntimeException error) {
            if (failure == null) return error;
            failure.addSuppressed(error);
        }
        return failure;
    }

    private static void closeAll(@NonNull List<Runnable> closers) {
        RuntimeException failure = null;
        for (Runnable closer : closers) {
            try {
                closer.run();
            } catch (RuntimeException error) {
                if (failure == null) failure = error;
                else failure.addSuppressed(error);
            }
        }
        if (failure != null) throw failure;
    }
}
