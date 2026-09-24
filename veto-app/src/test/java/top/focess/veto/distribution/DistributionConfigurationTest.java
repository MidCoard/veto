package top.focess.veto.distribution;

import static org.junit.jupiter.api.Assertions.*;

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
                                                                        "other_ask"))))
                .run(
                        context -> {
                            var aliases =
                                    context.getBean(PluginConfigurations.class).getToolNames();
                            assertEquals("interview", aliases.get("top.focess.builtin:ask_user"));
                            assertEquals("other_ask", aliases.get("other.plugin:ask"));
                            assertEquals("view_file", aliases.get("top.focess.builtin:view_file"));
                        });
    }
}
