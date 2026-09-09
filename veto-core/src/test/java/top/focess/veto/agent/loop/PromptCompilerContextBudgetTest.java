package top.focess.veto.agent.loop;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import top.focess.veto.agent.identity.SystemPromptResolver;
import top.focess.veto.agent.translation.VetoCapabilityTranslator;
import top.focess.veto.llm.core.*;

class PromptCompilerContextBudgetTest {
    private final @NonNull ObjectMapper mapper = new ObjectMapper();

    private @NonNull PromptCompiler compiler(int limit) {
        var compiler =
                new PromptCompiler(
                        new VetoCapabilityTranslator(),
                        new SystemPromptResolver(),
                        mapper,
                        "FULL_ACCESS");
        ReflectionTestUtils.setField(compiler, "maxInputTokens", limit);
        ReflectionTestUtils.setField(compiler, "contextFillRatio", 0.9);
        return compiler;
    }

    private @NonNull VetoRequest request(
            @NonNull String system, @NonNull List<ChatMessage> messages) {
        return new VetoRequest(
                system,
                messages.getLast().content(),
                List.of(),
                ProviderType.DEEPSEEK,
                "test-model",
                "unused",
                LlmOptions.defaults(),
                messages,
                mapper.createObjectNode(),
                null);
    }

    @Test
    void preservesTopicAndAnswerOrRejectsInsteadOfDroppingThem() {
        var compiler = compiler(1000);
        var messages =
                List.of(
                        ChatMessage.system("s".repeat(1700)),
                        ChatMessage.user("Explain TCP"),
                        ChatMessage.assistant("a".repeat(500)),
                        ChatMessage.user("Can you draw a diagram?"));
        var request = request(messages.getFirst().content(), messages);
        assertSame(request, compiler.fitRequest(request, 0.8));
        var error =
                assertThrows(IllegalStateException.class, () -> compiler.fitRequest(request, 1.2));
        assertTrue(error.getMessage().contains("No conversation history was removed"));
        assertEquals(messages, request.messages());
    }

    @Test
    void systemAloneCannotSilentlyExceedTheBudget() {
        var request = request("x".repeat(4000), List.of(ChatMessage.user("hello")));
        assertThrows(IllegalStateException.class, () -> compiler(1000).fitRequest(request));
    }

    @Test
    void countsResponseSchemaAndToolArguments() {
        var schema = mapper.createObjectNode().put("description", "s".repeat(4000));
        var request =
                new VetoRequest(
                        "system",
                        "continue",
                        List.of(),
                        ProviderType.DEEPSEEK,
                        "test-model",
                        "unused",
                        LlmOptions.defaults(),
                        List.of(ChatMessage.user("continue")),
                        schema,
                        null);
        assertThrows(IllegalStateException.class, () -> compiler(1000).fitRequest(request));
        var toolHistory =
                List.of(
                        ChatMessage.user("read"),
                        ChatMessage.assistantToolCall("c1", "read", "x".repeat(4000), "", null),
                        ChatMessage.toolResult("c1", "done"),
                        ChatMessage.user("continue"));
        assertThrows(
                IllegalStateException.class,
                () -> compiler(1000).fitRequest(request("system", toolHistory)));
    }

    @Test
    void explicitModelBudgetDoesNotRaiseUnknownModelsFallback() {
        var compiler = compiler(1000);
        var config = new ContextBudgetConfiguration();
        config.setModelInputTokens(Map.of("DEEPSEEK/test-model", 8000));
        compiler.configureContextBudgets(config);
        var request = request("x".repeat(4000), List.of(ChatMessage.user("hello")));
        assertSame(request, compiler.fitRequest(request));
        var other =
                new VetoRequest(
                        request.systemPrompt(),
                        request.userPrompt(),
                        request.tools(),
                        ProviderType.OPENAI,
                        request.modelName(),
                        "unused",
                        request.options(),
                        request.messages(),
                        request.responseSchema(),
                        null);
        assertThrows(IllegalStateException.class, () -> compiler.fitRequest(other));
        assertThrows(
                IllegalArgumentException.class,
                () -> config.setModelInputTokens(Map.of("DEEPSEEK/test-model", 0)));
    }
}
