package top.focess.veto.builtin;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import org.junit.jupiter.api.Test;
import top.focess.veto.api.agent.tool.ToolDocs;
import top.focess.veto.api.plugin.PluginContext;
import top.focess.veto.api.plugin.VetoPlugin;
import top.focess.veto.api.plugin.contract.JsonValue;
import top.focess.veto.api.plugin.contract.StandardContributionPoints;
import top.focess.veto.builtin.workspace.ViewFileTool;

class BuiltinPluginTest {
    @Test
    void realBuiltinPluginLoadsWithoutCoreAndContributesToolsAndSearchProviders() throws Exception {
        var plugin =
                ServiceLoader.load(ToolDocs.nonNullClass(VetoPlugin.class))
                        .findFirst()
                        .orElseThrow();
        try (plugin) {
            var contributions =
                    plugin.initialize(
                            new PluginContext(
                                    plugin.identity(),
                                    () -> {},
                                    () -> {
                                        throw new IllegalStateException(
                                                "Plugin context is not bound to a lifecycle owner");
                                    },
                                    Map.of()),
                            new JsonValue.ObjectValue(Map.of()));
            plugin.start();
            assertEquals(
                    List.of(
                            "answer_with_citations",
                            "ask_user",
                            "cancel_group_task",
                            "cancel_monitor",
                            "create_group",
                            "create_mate",
                            "create_monitor",
                            "create_task",
                            "delete_path",
                            "disband_group",
                            "find_files",
                            "forget_memory",
                            "grep_search",
                            "input_task",
                            "inspect_group",
                            "inspect_monitor",
                            "list_dir",
                            "load_skill",
                            "move_path",
                            "pause_monitor",
                            "post_message",
                            "read_github_repository",
                            "recall_memory",
                            "remove_mate",
                            "remove_node",
                            "replace_file_content",
                            "resume_monitor",
                            "run_command",
                            "run_task",
                            "stop_task",
                            "submit_plan",
                            "view_file",
                            "view_task",
                            "web_fetch",
                            "web_search",
                            "write_memory",
                            "write_to_file"),
                    contributions.entries().stream()
                            .filter(e -> e.point().equals(StandardContributionPoints.NATIVE_TOOLS))
                            .map(e -> e.localId())
                            .sorted()
                            .toList());
            assertEquals(52, contributions.entries().size());
            var featurePoints =
                    Map.of(
                            StandardContributionPoints.AGENT_WORK,
                            List.of("monitor-work"),
                            StandardContributionPoints.SESSION_LIFECYCLE,
                            List.of(
                                    "group-lifecycle",
                                    "monitor-lifecycle",
                                    "questions-lifecycle",
                                    "tasks-lifecycle"),
                            StandardContributionPoints.FRONTEND,
                            List.of("groups", "monitors", "questions", "tasks", "tools"),
                            StandardContributionPoints.AGENT_CONFIGURATION,
                            List.of("group-configuration"),
                            StandardContributionPoints.MODEL_RESPONSE,
                            List.of("responses"),
                            StandardContributionPoints.DATA_LIFECYCLE,
                            List.of("memory-data"));
            featurePoints.forEach(
                    (point, ids) ->
                            assertEquals(
                                    ids,
                                    contributions.entries().stream()
                                            .filter(entry -> entry.point().equals(point))
                                            .map(entry -> entry.localId())
                                            .sorted()
                                            .toList()));
            assertEquals(
                    List.of("brave", "duckduckgo"),
                    contributions.entries().stream()
                            .filter(e -> e.point().equals(StandardContributionPoints.SERVICES))
                            .map(e -> e.localId())
                            .sorted()
                            .toList());
            assertThrows(
                    ClassNotFoundException.class,
                    () -> Class.forName("top.focess.veto.agent.AgentRunner"));
            assertThrows(
                    SecurityException.class,
                    () -> new ViewFileTool().execute(new ViewFileTool.Args("unused", null, null)));
        }
    }
}
