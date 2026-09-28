package top.focess.veto.providers;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import top.focess.veto.api.plugin.*;
import top.focess.veto.api.plugin.contract.*;

class LlmProvidersPluginTest {
    @Test
    void loadsAndRegistersAllTransportsWithoutCoreOrSpring() throws Exception {
        var configuration = new JsonValue.ObjectValue(Map.of());
        var context =
                new PluginContext(
                        new PluginIdentity("top.focess.llm-providers", "1.0.100"),
                        () -> {},
                        () -> PluginState.NEW,
                        Map.of());
        try (var plugin = new LlmProvidersPlugin(context, configuration)) {
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
                            configuration);
            plugin.start();
            assertEquals(
                    Set.of("openai", "deepseek", "anthropic", "gemini"),
                    contributions.entries().stream()
                            .map(value -> value.localId())
                            .collect(Collectors.toSet()));
            assertTrue(
                    contributions.entries().stream()
                            .allMatch(
                                    value ->
                                            value.point()
                                                    .equals(
                                                            StandardContributionPoints
                                                                    .LLM_PROVIDERS)));
            assertThrows(
                    ClassNotFoundException.class,
                    () -> Class.forName("top.focess.veto.agent.AgentRunner"));
            assertThrows(
                    ClassNotFoundException.class,
                    () -> Class.forName("org.springframework.stereotype.Component"));
        }
    }
}
