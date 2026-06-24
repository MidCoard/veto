package top.focess.veto.llm.provider;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.Duration;
import java.util.List;
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
class DeepSeekProviderIntegrationTest {

    @Autowired private DeepSeekProvider provider;

    @Value("${veto.test.deepseek.api-key:}")
    private String apiKey;

    @Value("${veto.test.deepseek.model:deepseek-v4-pro}")
    private String model;

    @BeforeEach
    void requireKey() {
        assumeTrue(
                apiKey != null && !apiKey.isBlank() && !apiKey.startsWith("sk-placeholder"),
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
                        LlmOptions.defaults());

        ResolvedRequest resolved = new ResolvedRequest(request, "https://api.deepseek.com", apiKey);
        VetoResponse response = provider.execute(resolved);

        assertNotNull(response.thought(), "thought should not be null");
        assertNotNull(response.calls(), "calls list should not be null (may be empty if finished)");
        assertTrue(response.isFinished(), "simple prompt should finish immediately");
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
                        options);

        ResolvedRequest resolved = new ResolvedRequest(request, "https://api.deepseek.com", apiKey);
        VetoResponse response = provider.execute(resolved);

        assertNotNull(response.thought());
        assertFalse(response.thought().isBlank(), "thought should not be blank");
        assertTrue(response.isFinished(), "simple prompt should finish immediately");
    }
}
