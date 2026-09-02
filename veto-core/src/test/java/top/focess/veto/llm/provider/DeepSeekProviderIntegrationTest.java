package top.focess.veto.llm.provider;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.Duration;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import top.focess.veto.llm.core.LlmOptions;
import top.focess.veto.llm.core.ProviderType;
import top.focess.veto.llm.core.ResolvedRequest;
import top.focess.veto.llm.core.VetoRequest;
import top.focess.veto.llm.core.VetoResponse;

/** Integration test that calls the real DeepSeek API. Requires {@code application-local.yml}. */
@SpringBootTest
@ActiveProfiles("local")
@SuppressWarnings("initialization.field.uninitialized")
class DeepSeekProviderIntegrationTest {

    @Autowired private @NonNull DeepSeekProvider provider;

    @Value("${veto.test.deepseek.api-key}")
    private @NonNull String apiKey;

    @Value("${veto.test.deepseek.model}")
    private @NonNull String model;

    @BeforeEach
    void requireKey() {
        assumeTrue(
                !apiKey.isBlank() && !apiKey.startsWith("sk-placeholder"),
                "Skipped: set veto.test.deepseek.api-key in application-local.yml");
    }

    @Test
    void shouldReturnValidResponseForSimplePrompt() {
        VetoRequest request =
                new VetoRequest(
                        "You are a helpful assistant.",
                        "Say hello in exactly one word. Output only the word, nothing else.",
                        List.of(),
                        ProviderType.DEEPSEEK,
                        model,
                        "deepseek-key",
                        LlmOptions.defaults(),
                        List.of(),
                        null,
                        null);

        ResolvedRequest resolved = new ResolvedRequest(request, "https://api.deepseek.com", apiKey);
        VetoResponse response = provider.execute(resolved);

        requireThought(response.thought());
        // calls is OPTIONAL in the veto_pulse schema (a model that answers directly emits
        // thought + message, no calls). The real invariant is "no empty turn"
        // (prompt_react_syntax Rule 3): the response must carry at least one of
        // thought / message / calls, and a simple prompt should finish.
        assertTrue(
                response.thought() != null || response.message() != null || response.hasCalls(),
                "turn must produce some output (thought/message/calls)");
        assertFalse(response.hasCalls(), "simple prompt should finish immediately (no tool calls)");
    }

    @Test
    void shouldHandleTemperatureZero() {
        // Temperature 0 should give deterministic output
        LlmOptions options = new LlmOptions(0.0, null, 4096, Duration.ofSeconds(30));
        VetoRequest request =
                new VetoRequest(
                        "You are a helpful assistant.",
                        "Answer with exactly the single word 'hello' and nothing else.",
                        List.of(),
                        ProviderType.DEEPSEEK,
                        model,
                        "deepseek-key",
                        options,
                        List.of(),
                        null,
                        null);

        ResolvedRequest resolved = new ResolvedRequest(request, "https://api.deepseek.com", apiKey);
        VetoResponse response = provider.execute(resolved);

        String thought = requireThought(response.thought());
        assertFalse(thought.isBlank(), "thought should not be blank");
        assertFalse(response.hasCalls(), "simple prompt should finish immediately (no tool calls)");
    }

    private static @NonNull String requireThought(String thought) {
        if (thought != null) {
            return thought;
        }
        throw new AssertionError("thought should not be null");
    }
}
