package top.focess.veto.llm.client;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import top.focess.veto.util.Nullness;

class AnthropicLlmClientTest {
    @Test
    void preservesCacheBreakdownWithoutDoubleCountingInput() {
        var sdk = mock(ToolDocs.nonNullClass(AnthropicClient.class), RETURNS_DEEP_STUBS);
        var response = mock(ToolDocs.nonNullClass(Message.class), RETURNS_DEEP_STUBS);
        var block = text("{\"message\":\"ok\"}");
        when(response.content()).thenReturn(List.of(block));
        when(response.usage().inputTokens()).thenReturn(100L);
        when(response.usage().outputTokens()).thenReturn(5L);
        when(response.usage().cacheReadInputTokens()).thenReturn(Optional.of(800L));
        when(response.usage().cacheCreationInputTokens()).thenReturn(Optional.of(100L));
        when(sdk.messages().create(any(ToolDocs.nonNullClass(MessageCreateParams.class))))
                .thenReturn(response);
        LlmSystemUsage.begin();
        try {
            new AnthropicLlmClient(sdk, new ObjectMapper())
                    .complete(new ResolvedRequest(request(), null, "unused"));
            var usage = LlmSystemUsage.snapshot().getFirst();
            assertEquals(1000L, usage.promptTokens());
            assertEquals(800L, Nullness.requireNonNull(usage.cacheReadInputTokens()).longValue());
            assertEquals(
                    100L, Nullness.requireNonNull(usage.cacheCreationInputTokens()).longValue());
        } finally {
            LlmSystemUsage.drain();
        }
    }

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
        assertTrue(
                String.valueOf(params._additionalBodyProperties().get("tool_choice"))
                        .contains("auto"));
        assertTrue(params.system().toString().contains("Use native tool_use"));
        assertFalse(params.system().toString().contains("Emit JSON text only"));
        assertFalse(params.system().toString().contains("does not enable native tool execution"));
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
    void generationSchemaDisablesNativeToolsEvenWhenCatalogIsPresent() {
        var sdk = mock(ToolDocs.nonNullClass(AnthropicClient.class), RETURNS_DEEP_STUBS);
        var response = mock(ToolDocs.nonNullClass(Message.class), RETURNS_DEEP_STUBS);
        var finished = text("{\"message\":\"Finished\"}");
        when(response.content()).thenReturn(List.of(finished));
        when(sdk.messages().create(any(ToolDocs.nonNullClass(MessageCreateParams.class))))
                .thenReturn(response);
        @NonNull ToolDefinition tool = mock();
        when(tool.name()).thenReturn("view_file");
        when(tool.description()).thenReturn("Read a file");
        when(tool.inputSchema()).thenReturn(Map.of("type", "object"));
        var schema = new ObjectMapper().createObjectNode();
        schema.put("type", "object");
        schema.putObject("properties").putObject("message").put("type", "string");
        var request =
                new VetoRequest(
                        "System",
                        "Generate",
                        List.of(tool),
                        ProviderType.ANTHROPIC,
                        "model",
                        "key",
                        LlmOptions.defaults(),
                        List.of(),
                        schema,
                        null);
        var client = new AnthropicLlmClient(sdk, new ObjectMapper());
        client.complete(new ResolvedRequest(request, null, "unused"));
        var sent = ArgumentCaptor.forClass(ToolDocs.nonNullClass(MessageCreateParams.class));
        verify(sdk.messages()).create(sent.capture());
        assertTrue(
                String.valueOf(sent.getValue()._additionalBodyProperties().get("tool_choice"))
                        .contains("none"));
        assertTrue(
                sent.getValue()
                        .system()
                        .toString()
                        .contains("does not enable native tool execution"));
        assertFalse(sent.getValue().system().toString().contains("Use native tool_use"));
        var nativeCall = mock(ToolDocs.nonNullClass(ContentBlock.class), RETURNS_DEEP_STUBS);
        when(nativeCall.isToolUse()).thenReturn(true);
        when(response.content()).thenReturn(List.of(nativeCall));
        assertThrows(
                ToolDocs.nonNullClass(ModelSchemaException.class),
                () -> client.complete(new ResolvedRequest(request, null, "unused")));
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
    void rejectsNativeCallsWhenNoToolsAreAvailable() {
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

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void acceptsAlternatingJsonAndNativeCallsWithoutLosingArguments(boolean guided)
            throws Exception {
        var sdk = mock(ToolDocs.nonNullClass(AnthropicClient.class), RETURNS_DEEP_STUBS);
        var response = mock(ToolDocs.nonNullClass(Message.class), RETURNS_DEEP_STUBS);
        when(sdk.messages().create(any(ToolDocs.nonNullClass(MessageCreateParams.class))))
                .thenReturn(response);
        @NonNull ToolDefinition tool = mock();
        when(tool.name()).thenReturn("list_dir");
        when(tool.description()).thenReturn("List a directory");
        when(tool.inputSchema())
                .thenReturn(
                        Map.of(
                                "type",
                                "object",
                                "properties",
                                Map.of("absolutePath", Map.of("type", "string"))));
        List<ToolDefinition> tools = List.of(tool);
        var request =
                new VetoRequest(
                        "System",
                        "Continue",
                        tools,
                        ProviderType.ANTHROPIC,
                        "minimax",
                        "key",
                        LlmOptions.defaults(),
                        List.of(),
                        new VetoCapabilityTranslator().vetoResponseSchema(guided, tools),
                        null);
        var client = new AnthropicLlmClient(sdk, new ObjectMapper());
        var nativeCall = mock(ToolDocs.nonNullClass(ContentBlock.class), RETURNS_DEEP_STUBS);
        when(nativeCall.isToolUse()).thenReturn(true);
        when(nativeCall.asToolUse().name()).thenReturn("list_dir");
        when(nativeCall.asToolUse()._input())
                .thenReturn(JsonValue.from(Map.of("absolutePath", "/workspace")));
        var jsonCall =
                text(
                        "{\"calls\":[{\"tool_name\":\"list_dir\",\"args\":{\"absolutePath\":\"/workspace\"}}]}");
        // The real session alternated formats: a native response must not require a retry
        // that discards the intended write and causes another directory read instead.
        for (ContentBlock block : List.of(jsonCall, nativeCall, jsonCall, nativeCall)) {
            when(response.content()).thenReturn(List.of(block));
            var result =
                    new ObjectMapper()
                            .readTree(
                                    client.complete(new ResolvedRequest(request, null, "unused"))
                                            .rawResponse());
            assertEquals("list_dir", result.path("calls").get(0).path("tool_name").asText());
            assertEquals(
                    "/workspace",
                    result.path("calls").get(0).path("args").path("absolutePath").asText());
        }
        // Never silently unwrap malformed provider arguments: the runtime must reject them.
        when(nativeCall.asToolUse()._input())
                .thenReturn(JsonValue.from(Map.of("args", Map.of("absolutePath", "/workspace"))));
        when(response.content()).thenReturn(List.of(nativeCall));
        var malformed =
                new ObjectMapper()
                        .readTree(
                                client.complete(new ResolvedRequest(request, null, "unused"))
                                        .rawResponse());
        assertTrue(malformed.path("calls").get(0).path("args").has("args"));
        assertFalse(malformed.path("calls").get(0).path("args").has("absolutePath"));
        when(nativeCall.asToolUse().name()).thenReturn("unavailable_tool");
        assertThrows(
                ToolDocs.nonNullClass(ModelSchemaException.class),
                () -> client.complete(new ResolvedRequest(request, null, "unused")));
    }

    @Test
    void rejectsMixedJsonCallsAndNativeCallsInsteadOfDroppingEither() {
        var sdk = mock(ToolDocs.nonNullClass(AnthropicClient.class), RETURNS_DEEP_STUBS);
        var response = mock(ToolDocs.nonNullClass(Message.class), RETURNS_DEEP_STUBS);
        var nativeCall = mock(ToolDocs.nonNullClass(ContentBlock.class), RETURNS_DEEP_STUBS);
        when(nativeCall.isToolUse()).thenReturn(true);
        var jsonCall = text("{\"calls\":[{\"tool_name\":\"list_dir\",\"args\":{}}]}");
        when(response.content()).thenReturn(List.of(jsonCall, nativeCall));
        when(sdk.messages().create(any(ToolDocs.nonNullClass(MessageCreateParams.class))))
                .thenReturn(response);
        var failure =
                assertThrows(
                        ToolDocs.nonNullClass(ModelSchemaException.class),
                        () ->
                                new AnthropicLlmClient(sdk, new ObjectMapper())
                                        .complete(new ResolvedRequest(request(), null, "unused")));
        assertTrue(String.valueOf(failure.getMessage()).contains("mixed"));
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
