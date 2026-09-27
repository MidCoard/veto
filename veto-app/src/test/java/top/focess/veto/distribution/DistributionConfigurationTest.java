package top.focess.veto.distribution;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import top.focess.veto.api.agent.tool.ToolDocs;
import top.focess.veto.integration.plugins.PluginConfigurations;

class DistributionConfigurationTest {
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(PluginConfigurations.class)
    static class Binding {}

    @Test
    void defaultsBelongToDistributionAndOperatorAliasesWin() {
        var core =
                new ApplicationContextRunner()
                        .withUserConfiguration(ToolDocs.nonNullClass(Binding.class));
        core.run(
                context ->
                        assertTrue(
                                context.getBean(PluginConfigurations.class)
                                        .getToolNames()
                                        .isEmpty()));
        var distribution =
                core.withUserConfiguration(ToolDocs.nonNullClass(DistributionConfiguration.class));
        distribution.run(
                context -> {
                    var aliases = context.getBean(PluginConfigurations.class).getToolNames();
                    assertEquals("ask_user", aliases.get("top.focess.builtin:ask_user"));
                    assertEquals("create_group", aliases.get("top.focess.builtin:create_group"));
                    assertEquals("view_file", aliases.get("top.focess.builtin:view_file"));
                    assertEquals(37, aliases.size());
                    var configuration = context.getBean(PluginConfigurations.class);
                    var values = configuration.getConfiguration().get("top.focess.builtin");
                    var roots = configuration.getCatalogueRoots().get("top.focess.builtin");
                    if (values == null || roots == null)
                        throw new AssertionError("Missing builtin defaults");
                    assertEquals("jpa", values.get("memory-store"));
                    String personal = roots.get("personal");
                    if (personal == null) throw new AssertionError("Missing personal skills root");
                    assertEquals(
                            Path.of(System.getProperty("user.home"), ".veto", "skills"),
                            Path.of(personal).normalize());
                });
        distribution
                .withInitializer(
                        context ->
                                context.getEnvironment()
                                        .getPropertySources()
                                        .addFirst(
                                                new MapPropertySource(
                                                        "operator",
                                                        Map.of(
                                                                "veto.plugins.tool-names[top.focess.builtin:ask_user]",
                                                                        "interview",
                                                                "veto.plugins.tool-names[other.plugin:ask]",
                                                                        "other_ask",
                                                                "veto.plugins.configuration[top.focess.builtin][memory-store]",
                                                                        "memory"))))
                .run(
                        context -> {
                            var aliases =
                                    context.getBean(PluginConfigurations.class).getToolNames();
                            assertEquals("interview", aliases.get("top.focess.builtin:ask_user"));
                            assertEquals("other_ask", aliases.get("other.plugin:ask"));
                            assertEquals("view_file", aliases.get("top.focess.builtin:view_file"));
                            var values =
                                    context.getBean(PluginConfigurations.class)
                                            .getConfiguration()
                                            .get("top.focess.builtin");
                            if (values == null)
                                throw new AssertionError("Missing builtin configuration");
                            assertEquals("memory", values.get("memory-store"));
                        });
    }
}
