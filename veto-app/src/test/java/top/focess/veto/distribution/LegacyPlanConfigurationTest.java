package top.focess.veto.distribution;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;
import top.focess.veto.api.agent.tool.ToolDocs;
import top.focess.veto.integration.plugins.PluginConfigurations;

class LegacyPlanConfigurationTest {
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(PluginConfigurations.class)
    @Import(LegacyPlanConfiguration.class)
    static class Binding {}

    @Test
    void bridgesAfterBindingAndPreservesExplicitPluginValues() {
        new ApplicationContextRunner()
                .withUserConfiguration(ToolDocs.nonNullClass(Binding.class))
                .withPropertyValues("veto.plan.max-steps=5")
                .run(
                        context -> {
                            assertNull(context.getStartupFailure());
                            assertEquals(
                                    Map.of("plan-max-steps", "5"),
                                    context.getBean(PluginConfigurations.class)
                                            .getConfiguration()
                                            .get("top.focess.builtin"));
                        });
        new ApplicationContextRunner()
                .withUserConfiguration(ToolDocs.nonNullClass(Binding.class))
                .withPropertyValues("veto.plan.max-steps=5")
                .withInitializer(
                        context ->
                                context.getEnvironment()
                                        .getPropertySources()
                                        .addFirst(
                                                new MapPropertySource(
                                                        "operator",
                                                        Map.of(
                                                                "veto.plugins.configuration[top.focess.builtin][plan-max-steps]",
                                                                        "8",
                                                                "veto.plugins.configuration[top.focess.builtin][reader-model-tier]",
                                                                        "MID"))))
                .run(
                        context -> {
                            assertNull(context.getStartupFailure());
                            assertEquals(
                                    Map.of("plan-max-steps", "8", "reader-model-tier", "MID"),
                                    context.getBean(PluginConfigurations.class)
                                            .getConfiguration()
                                            .get("top.focess.builtin"));
                        });
    }

    @Test
    void absentLegacySettingDoesNotInstallBuiltinDefaults() {
        var configuration = new PluginConfigurations();
        new LegacyPlanConfiguration(new MockEnvironment())
                .postProcessBeforeInitialization(configuration, "plugins");
        assertTrue(configuration.getConfiguration().isEmpty());
    }
}
