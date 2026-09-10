package top.focess.veto.llm.client;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import top.focess.veto.agent.tool.ToolDocs;
import top.focess.veto.agent.translation.VetoCapabilityTranslator;
import top.focess.veto.llm.core.*;
import top.focess.veto.llm.exceptions.ModelSchemaException;

class AnthropicLlmClientTest {
    @Test
    void actualMessageParamsUseCitationMessageBoundaries() {
        @NonNull AnthropicClient sdk = mock();
        var request =
                new VetoRequest(
                        "system",
                        "fallback",
                        List.of(),
                        ProviderType.ANTHROPIC,
                        "model",
                        "key",
                        LlmOptions.defaults(),
                        List.of(
                                ChatMessage.system("system"),
                                ChatMessage.user("first"),
                                ChatMessage.user("second"),
                                ChatMessage.assistant(""),
                                ChatMessage.assistant("answer"),
                                ChatMessage.toolResult("call", "result")),
                        null,
                        null);
        Object actual =
                ReflectionTestUtils.invokeMethod(
                        new AnthropicLlmClient(sdk, new ObjectMapper()),
                        "toMessageParams",
                        request);
        if (!(actual instanceof List<?> parameters))
            throw new AssertionError("Expected provider messages");
        assertEquals(ProviderMessages.groups(request).size(), parameters.size());
        assertEquals(3, parameters.size());
        assertTrue(String.valueOf(parameters.getFirst()).contains("first"));
        assertTrue(String.valueOf(parameters.getFirst()).contains("second"));
    }

    private static final @NonNull String GUIDE =
            "{\"guide\":{\"actions\":[{\"id\":\"done\",\"label\":\"Finish\",\"type\":\"STOP\"}]}}";

    private static @NonNull VetoRequest request() {
        return new VetoRequest(
                "Current system",
                "Finish",
                List.of(),
                ProviderType.ANTHROPIC,
                "claude-test",
                "test-key",
                LlmOptions.defaults(),
                List.of(),
                new VetoCapabilityTranslator().vetoResponseSchema(true),
                null);
    }

    private static @NonNull ContentBlock text(@NonNull String value) {
        var block = mock(ToolDocs.nonNullClass(ContentBlock.class), RETURNS_DEEP_STUBS);
        when(block.isText()).thenReturn(true);
        when(block.asText().text()).thenReturn(value);
        return block;
    }

    @Test
    void preservesDirectGuideAndSendsExactSchemaWithoutInventingFeatures() throws Exception {
        var sdk = mock(ToolDocs.nonNullClass(AnthropicClient.class), RETURNS_DEEP_STUBS);
        var response = mock(ToolDocs.nonNullClass(Message.class), RETURNS_DEEP_STUBS);
        var guideText = text(GUIDE);
        when(response.content()).thenReturn(List.of(guideText));
        when(sdk.messages().create(any(ToolDocs.nonNullClass(MessageCreateParams.class))))
                .thenReturn(response);
        var request = request();
        var result =
                new AnthropicLlmClient(sdk, new ObjectMapper())
                        .complete(new ResolvedRequest(request, null, "unused"));
        assertEquals(GUIDE, result.rawResponse());
        assertFalse(result.rawResponse().contains("features"));
        var sent = ArgumentCaptor.forClass(ToolDocs.nonNullClass(MessageCreateParams.class));
        verify(sdk.messages()).create(sent.capture());
        assertTrue(sent.getValue().system().toString().contains("guide"));
        assertTrue(sent.getValue().system().toString().contains("conditional_goto"));
        assertTrue(
                sent.getValue().tools().isEmpty(),
                "generation/tool-free request must not invent native tools");
    }

    @Test
    void rejectsMixedNativeCallsAndGuideInsteadOfDroppingGuide() {
        var sdk = mock(ToolDocs.nonNullClass(AnthropicClient.class), RETURNS_DEEP_STUBS);
        var response = mock(ToolDocs.nonNullClass(Message.class), RETURNS_DEEP_STUBS);
        var nativeCall = mock(ToolDocs.nonNullClass(ContentBlock.class), RETURNS_DEEP_STUBS);
        when(nativeCall.isToolUse()).thenReturn(true);
        var guideText = text(GUIDE);
        when(response.content()).thenReturn(List.of(guideText, nativeCall));
        when(sdk.messages().create(any(ToolDocs.nonNullClass(MessageCreateParams.class))))
                .thenReturn(response);
        var client = new AnthropicLlmClient(sdk, new ObjectMapper());
        assertThrows(
                ToolDocs.nonNullClass(ModelSchemaException.class),
                () -> client.complete(new ResolvedRequest(request(), null, "unused")));
    }
}
