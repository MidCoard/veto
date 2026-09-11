package top.focess.veto.llm.client;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import top.focess.veto.agent.tool.ToolDocs;
import top.focess.veto.agent.translation.VetoCapabilityTranslator;
import top.focess.veto.llm.core.*;
import top.focess.veto.llm.exceptions.ModelSchemaException;

class AnthropicLlmClientTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void responseChannelPreservesContractsHistoryAndText(boolean guided) throws Exception {
        var sdk = mock(ToolDocs.nonNullClass(AnthropicClient.class), RETURNS_DEEP_STUBS);
        var response = mock(ToolDocs.nonNullClass(Message.class), RETURNS_DEEP_STUBS);
        var finished = text("Finished");
        when(response.content()).thenReturn(List.of(finished));
        when(sdk.messages().create(any(ToolDocs.nonNullClass(MessageCreateParams.class))))
                .thenReturn(response);
        @NonNull ToolDefinition tool = mock();
        when(tool.name()).thenReturn("view_file");
        when(tool.description()).thenReturn("Read a file");
        when(tool.inputSchema())
                .thenReturn(
                        Map.of(
                                "type",
                                "object",
                                "properties",
                                Map.of("absolutePath", Map.of("type", "string")),
                                "required",
                                List.of("absolutePath")));
        List<ToolDefinition> tools = List.of(tool);
        var request =
                new VetoRequest(
                        "System",
                        "Continue",
                        tools,
                        ProviderType.ANTHROPIC,
                        "model",
                        "key",
                        LlmOptions.defaults(),
                        List.of(
                                ChatMessage.user("Read"),
                                ChatMessage.assistantToolCall("prior", "view_file", "{}", "", null),
                                ChatMessage.toolResult("prior", "Known content")),
                        new VetoCapabilityTranslator().vetoResponseSchema(guided, tools),
                        null);
        var client = new AnthropicLlmClient(sdk, new ObjectMapper());
        assertEquals(
                "{\"message\":\"Finished\"}",
                client.complete(new ResolvedRequest(request, null, "unused")).rawResponse());
        var sent = ArgumentCaptor.forClass(ToolDocs.nonNullClass(MessageCreateParams.class));
        verify(sdk.messages()).create(sent.capture());
        var params = sent.getValue();
        assertEquals(1, params.tools().orElseThrow().size());
        assertTrue(params.system().toString().contains("absolutePath"));
        assertEquals(guided, params._additionalBodyProperties().containsKey("tool_choice"));
        if (guided)
            assertTrue(
                    String.valueOf(params._additionalBodyProperties().get("tool_choice"))
                            .contains("none"));
        assertEquals(
                "prior",
                params.messages()
                        .getLast()
                        .content()
                        .asBlockParams()
                        .getFirst()
                        .asToolResult()
                        .toolUseId());
        assertTrue(params.messages().getLast().content().toString().contains("Known content"));
        var ordinaryCall =
                text(
                        "{\"calls\":[{\"tool_name\":\"view_file\",\"args\":{\"absolutePath\":\"D:/notes.txt\"}}]}");
        when(response.content()).thenReturn(List.of(ordinaryCall));
        assertTrue(
                client.complete(new ResolvedRequest(request, null, "unused"))
                        .rawResponse()
                        .contains("D:/notes.txt"));
    }

    @Test
    void rejectsLeakedMinimaxControlTextButPreservesExplicitJsonAnswers() {
        var sdk = mock(ToolDocs.nonNullClass(AnthropicClient.class), RETURNS_DEEP_STUBS);
        var response = mock(ToolDocs.nonNullClass(Message.class), RETURNS_DEEP_STUBS);
        when(sdk.messages().create(any(ToolDocs.nonNullClass(MessageCreateParams.class))))
                .thenReturn(response);
        var client = new AnthropicLlmClient(sdk, new ObjectMapper());
        String leaked = "]<]minimax[>[<tool_call>\n]<]minimax[>[<invoke name=\"view_file\">";
        var leakedBlock = text(leaked);
        when(response.content()).thenReturn(List.of(leakedBlock));
        var failure =
                assertThrows(
                        ToolDocs.nonNullClass(ModelSchemaException.class),
                        () -> client.complete(new ResolvedRequest(request(), null, "unused")));
        String failureMessage = failure.getMessage();
        if (failureMessage == null) throw new AssertionError("Expected safe schema failure detail");
        assertFalse(failureMessage.contains(leaked));
        String quoted = "{\"message\":\"The marker ]<]minimax[>[ is provider syntax.\"}";
        var quotedBlock = text(quoted);
        when(response.content()).thenReturn(List.of(quotedBlock));
        assertEquals(
                quoted,
                client.complete(new ResolvedRequest(request(), null, "unused")).rawResponse());
    }

    @Test
    void rejectsNativeOnlyWhenJsonProgramChannelIsSelected() {
        var sdk = mock(ToolDocs.nonNullClass(AnthropicClient.class), RETURNS_DEEP_STUBS);
        var response = mock(ToolDocs.nonNullClass(Message.class), RETURNS_DEEP_STUBS);
        var nativeCall = mock(ToolDocs.nonNullClass(ContentBlock.class), RETURNS_DEEP_STUBS);
        when(nativeCall.isToolUse()).thenReturn(true);
        when(response.content()).thenReturn(List.of(nativeCall));
        when(sdk.messages().create(any(ToolDocs.nonNullClass(MessageCreateParams.class))))
                .thenReturn(response);
        assertThrows(
                ToolDocs.nonNullClass(ModelSchemaException.class),
                () ->
                        new AnthropicLlmClient(sdk, new ObjectMapper())
                                .complete(new ResolvedRequest(request(), null, "unused")));
    }

    @Test
    void recoveryObservationDoesNotReplaceSuccessfulToolResult() {
        @NonNull AnthropicClient sdk = mock();
        var request =
                new VetoRequest(
                        "system",
                        "new request",
                        List.of(),
                        ProviderType.ANTHROPIC,
                        "model",
                        "key",
                        LlmOptions.defaults(),
                        List.of(
                                ChatMessage.user("read the file"),
                                ChatMessage.assistantToolCall(
                                        "fresh-call", "view_file", "{}", "", null),
                                ChatMessage.toolResult("fresh-call", "alpha=17\nbeta=25"),
                                ChatMessage.user(
                                        "[Runtime recovery observation] old-attempt was interrupted")),
                        null,
                        null);
        Object actual =
                ReflectionTestUtils.invokeMethod(
                        new AnthropicLlmClient(sdk, new ObjectMapper()),
                        "toMessageParams",
                        request);
        if (!(actual instanceof List<?> parameters)) throw new AssertionError("Expected messages");
        assertEquals(3, parameters.size());
        if (!(parameters.getLast() instanceof MessageParam last))
            throw new AssertionError("Expected SDK message");
        var blocks = last.content().asBlockParams();
        assertEquals(2, blocks.size());
        var result = blocks.getFirst().asToolResult();
        assertEquals("fresh-call", result.toolUseId());
        assertEquals(false, result.isError().orElseThrow());
        assertTrue(result.content().orElseThrow().toString().contains("alpha=17"));
        assertTrue(blocks.getLast().asText().text().contains("old-attempt"));
    }

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
