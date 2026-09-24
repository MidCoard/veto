package top.focess.veto.distribution;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;
import top.focess.veto.api.agent.tool.ToolDocs;
import top.focess.veto.integration.plugins.PluginConfigurations;

class LegacyReaderConfigurationTest {
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(PluginConfigurations.class)
    @Import(LegacyReaderConfiguration.class)
    static class Binding {}

    @Test
    void mapsFiveReaderSettingsAfterBindingWithExplicitPluginValuesWinning() {
        new ApplicationContextRunner()
                .withUserConfiguration(ToolDocs.nonNullClass(Binding.class))
                .withPropertyValues(
                        "veto.webfetch.model-tier=MID",
                        "veto.webfetch.max-rounds=9",
                        "veto.webfetch.timeout-seconds=80",
                        "veto.webfetch.max-input-tokens=24000",
                        "veto.webfetch.max-output-tokens=2000",
                        "veto.webfetch.fetch.max-chars=12")
                .withInitializer(
                        context ->
                                context.getEnvironment()
                                        .getPropertySources()
                                        .addFirst(
                                                new MapPropertySource(
                                                        "operator",
                                                        Map.of(
                                                                "veto.plugins.configuration[top.focess.builtin][reader-model-tier]",
                                                                        "TOP",
                                                                "veto.plugins.configuration[top.focess.builtin][group-mate-tier]",
                                                                        "MID"))))
                .run(
                        context -> {
                            assertNull(context.getStartupFailure());
                            var values =
                                    context.getBean(PluginConfigurations.class)
                                            .getConfiguration()
                                            .get("top.focess.builtin");
                            if (values == null)
                                throw new AssertionError("Missing builtin configuration");
                            assertEquals(
                                    Map.of(
                                            "reader-model-tier",
                                            "TOP",
                                            "reader-max-rounds",
                                            "9",
                                            "reader-timeout-seconds",
                                            "80",
                                            "reader-max-input-tokens",
                                            "24000",
                                            "reader-max-output-tokens",
                                            "2000",
                                            "group-mate-tier",
                                            "MID"),
                                    values);
                        });
    }

    @Test
    void absentLegacySettingsDoNotInventPluginOrHttpConfiguration() {
        var environment = new MockEnvironment();
        var configuration = new PluginConfigurations();
        new LegacyReaderConfiguration(environment)
                .postProcessBeforeInitialization(configuration, "plugins");
        assertTrue(configuration.getConfiguration().isEmpty());
        new LegacyHttpEnvironment()
                .postProcessEnvironment(
                        environment, new SpringApplication(ToolDocs.nonNullClass(Binding.class)));
        assertNull(environment.getProperty("veto.http.timeout-seconds"));
        assertNull(environment.getProperty("veto.http.max-chars"));
        assertNull(environment.getProperty("veto.http.allow-private-addresses"));
    }

    @Test
    void legacyHttpFallbackIsAvailableBeforeHostBindingAndCannotOverrideNewPolicy() {
        var environment =
                new MockEnvironment()
                        .withProperty("veto.webfetch.fetch.timeout-seconds", "15")
                        .withProperty("veto.webfetch.fetch.max-chars", "12345")
                        .withProperty("veto.webfetch.fetch.allow-private-addresses", "true")
                        .withProperty("veto.http.allow-private-addresses", "false")
                        .withProperty("veto.http.timeout-seconds", "7");
        var bridge = new LegacyHttpEnvironment();
        bridge.postProcessEnvironment(
                environment, new SpringApplication(ToolDocs.nonNullClass(Binding.class)));
        bridge.postProcessEnvironment(
                environment, new SpringApplication(ToolDocs.nonNullClass(Binding.class)));
        assertEquals("7", environment.getProperty("veto.http.timeout-seconds"));
        assertEquals("12345", environment.getProperty("veto.http.max-chars"));
        assertEquals("false", environment.getProperty("veto.http.allow-private-addresses"));
    }
}
