package top.focess.veto.distribution;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.env.MockEnvironment;
import top.focess.veto.api.agent.tool.ToolDocs;
import top.focess.veto.integration.plugins.PluginConfigurations;

class LegacyGroupConfigurationTest {
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(PluginConfigurations.class)
    @Import(LegacyGroupConfiguration.class)
    static class BindingConfiguration {}

    @Test
    void bridgesAfterActualConfigurationPropertiesBinding() {
        new ApplicationContextRunner()
                .withUserConfiguration(ToolDocs.nonNullClass(BindingConfiguration.class))
                .withPropertyValues(
                        "veto.group.mate.tier=MID",
                        "veto.group.tick.interval-ms=2500",
                        "veto.plugins.configuration[top.focess.builtin][group-mate-tier]=TOP")
                .run(
                        context -> {
                            assertNull(context.getStartupFailure());
                            var values =
                                    context.getBean(PluginConfigurations.class)
                                            .getConfiguration()
                                            .get("top.focess.builtin");
                            if (values == null)
                                throw new AssertionError("Missing builtin configuration");
                            assertEquals("TOP", values.get("group-mate-tier"));
                            assertEquals("2500", values.get("group-tick-interval-ms"));
                        });
    }

    @Test
    void mapsLegacyAliasesWithoutOverwritingExplicitPluginValuesOrBudget() {
        var environment =
                new MockEnvironment()
                        .withProperty("veto.group.tick.interval-ms", "2500")
                        .withProperty("veto.group.leader.tier", "TOP")
                        .withProperty("veto.group.mate.tier", "MID")
                        .withProperty("veto.group.mate.system-prompt-base", "old guidance")
                        .withProperty("veto.group.skillsets.reader.tier", "LOW")
                        .withProperty(
                                "veto.group.skillsets.reader.system-prompt-base", "read carefully")
                        .withProperty("veto.group.mate.max-calls", "999");
        var configuration = new PluginConfigurations();
        configuration.setConfiguration(
                Map.of(
                        "top.focess.builtin",
                        Map.of("group-mate-tier", "HIGH"),
                        "other",
                        Map.of("key", "value")));
        var bridge = new LegacyGroupConfiguration(environment);
        assertSame(
                configuration,
                bridge.postProcessBeforeInitialization(configuration, "pluginConfigurations"));
        var builtin = configuration.getConfiguration().get("top.focess.builtin");
        if (builtin == null) throw new AssertionError("Missing builtin configuration");
        assertEquals("2500", builtin.get("group-tick-interval-ms"));
        assertEquals("HIGH", builtin.get("group-mate-tier"));
        assertEquals("old guidance", builtin.get("group-mate-guidance"));
        assertEquals("LOW", builtin.get("group-skillset.reader.tier"));
        assertEquals("read carefully", builtin.get("group-skillset.reader.guidance"));
        assertEquals(Map.of("key", "value"), configuration.getConfiguration().get("other"));
        assertTrue(builtin.keySet().stream().noneMatch(key -> key.contains("max-calls")));
        bridge.postProcessBeforeInitialization(configuration, "pluginConfigurations");
        assertEquals(builtin, configuration.getConfiguration().get("top.focess.builtin"));
    }
}
