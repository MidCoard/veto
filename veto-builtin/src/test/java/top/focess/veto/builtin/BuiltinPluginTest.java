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
    void realBuiltinPluginLoadsWithoutCoreAndContributesWorkspaceTools() throws Exception {
        var plugin =
                ServiceLoader.load(ToolDocs.nonNullClass(VetoPlugin.class))
                        .findFirst()
                        .orElseThrow();
        try (plugin) {
            var contributions =
                    plugin.initialize(
                            new PluginContext(plugin.identity()),
                            new JsonValue.ObjectValue(Map.of()));
            plugin.start();
            assertEquals(
                    List.of(
                            "delete_path",
                            "find_files",
                            "grep_search",
                            "list_dir",
                            "move_path",
                            "replace_file_content",
                            "view_file",
                            "write_to_file"),
                    contributions.entries().stream().map(e -> e.localId()).sorted().toList());
            assertTrue(
                    contributions.entries().stream()
                            .allMatch(
                                    e ->
                                            e.point()
                                                    .equals(
                                                            StandardContributionPoints
                                                                    .NATIVE_TOOLS)));
            assertThrows(
                    ClassNotFoundException.class,
                    () -> Class.forName("top.focess.veto.agent.AgentRunner"));
            assertThrows(
                    ClassNotFoundException.class,
                    () -> Class.forName("org.springframework.stereotype.Component"));
            assertThrows(
                    SecurityException.class,
                    () -> new ViewFileTool().execute(new ViewFileTool.Args("unused", null, null)));
        }
    }
}
