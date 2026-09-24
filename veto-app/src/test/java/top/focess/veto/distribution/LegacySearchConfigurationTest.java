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

class LegacySearchConfigurationTest {
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(PluginConfigurations.class)
    @Import(LegacySearchConfiguration.class)
    static class BindingConfiguration {}

    @Test
    void aliasesMergeAfterPluginConfigurationBinding() {
        new ApplicationContextRunner()
                .withUserConfiguration(ToolDocs.nonNullClass(BindingConfiguration.class))
                .withPropertyValues(
                        "veto.websearch.provider=brave",
                        "veto.websearch.brave.api-key=legacy-key",
                        "veto.plugins.configuration[top.focess.builtin][search-provider]=third-party")
                .run(
                        context -> {
                            assertNull(context.getStartupFailure());
                            assertEquals(
                                    Map.of(
                                            "search-provider",
                                            "third-party",
                                            "brave-api-key",
                                            "legacy-key"),
                                    context.getBean(PluginConfigurations.class)
                                            .getConfiguration()
                                            .get("top.focess.builtin"));
                        });
    }

    @Test
    void defaultsRemainInBuiltinAndLegacyAliasesPreserveExplicitSettings() {
        var config = new PluginConfigurations();
        new LegacySearchConfiguration(new MockEnvironment())
                .postProcessBeforeInitialization(config, "plugins");
        assertTrue(config.getConfiguration().isEmpty());
        var environment =
                new MockEnvironment()
                        .withProperty("veto.websearch.provider", "brave")
                        .withProperty("BRAVE_API_KEY", "environment-key");
        new LegacySearchConfiguration(environment)
                .postProcessBeforeInitialization(config, "plugins");
        assertEquals(
                Map.of("search-provider", "brave", "brave-api-key", "environment-key"),
                config.getConfiguration().get("top.focess.builtin"));
        config.setConfiguration(
                Map.of(
                        "top.focess.builtin",
                        Map.of("search-provider", "third-party", "brave-api-key", "explicit"),
                        "other",
                        Map.of("x", "y")));
        environment.withProperty("veto.websearch.brave.api-key", "legacy-property");
        new LegacySearchConfiguration(environment)
                .postProcessBeforeInitialization(config, "plugins");
        assertEquals(
                Map.of("search-provider", "third-party", "brave-api-key", "explicit"),
                config.getConfiguration().get("top.focess.builtin"));
        assertEquals(Map.of("x", "y"), config.getConfiguration().get("other"));
        var legacy = new PluginConfigurations();
        new LegacySearchConfiguration(environment)
                .postProcessBeforeInitialization(legacy, "plugins");
        assertEquals(
                Map.of("search-provider", "brave", "brave-api-key", "legacy-property"),
                legacy.getConfiguration().get("top.focess.builtin"));
    }
}
