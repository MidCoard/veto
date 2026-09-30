package top.focess.veto.providers;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.veto.api.plugin.*;
import top.focess.veto.api.plugin.contract.*;
import top.focess.veto.api.plugin.contribution.Contribution;

class LlmProvidersPluginTest {
    @Test
    void loadsAndRegistersAllTransportsWithoutCoreOrSpring() throws Exception {
        var configuration = new JsonValue.ObjectValue(Map.of());
        var registrations = new ArrayList<@NonNull Contribution<?>>();
        var context =
                new PluginContext(
                        new PluginIdentity("top.focess.llm-providers", "1.0.100"),
                        () -> {},
                        () -> PluginState.NEW,
                        Map.of(),
                        Map.of(StandardContributionPoints.LLM_PROVIDERS, registrations::add));
        try (var plugin = new LlmProvidersPlugin(context, configuration)) {
            plugin.start();
            assertEquals(
                    Set.of("openai", "deepseek", "anthropic", "gemini"),
                    registrations.stream()
                            .map(value -> value.localId())
                            .collect(Collectors.toSet()));
            assertTrue(
                    registrations.stream()
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
