package top.focess.veto.providers;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.veto.api.agent.tool.ToolDocs;
import top.focess.veto.api.agent.tool.ToolDocumentation;
import top.focess.veto.api.llm.LlmClient;
import top.focess.veto.api.llm.LlmOptions;
import top.focess.veto.api.llm.NativeToolState;
import top.focess.veto.api.llm.ProviderType;
import top.focess.veto.api.llm.ResolvedRequest;
import top.focess.veto.api.llm.ResponseContract;
import top.focess.veto.api.llm.ToolCall;
import top.focess.veto.api.llm.ToolDefinition;
import top.focess.veto.api.llm.VetoRequest;
import top.focess.veto.api.llm.VetoResponse;
import top.focess.veto.api.llm.exceptions.ModelSchemaException;
import top.focess.veto.llm.core.*;
import top.focess.veto.llm.provider.AbstractLlmProvider;
import top.focess.veto.observability.AuditLogger;

class NativeToolResponsesTest {

    @Test
    void predicateContractRejectsExplanationsAndToolsAtTheProviderBoundary() {
        var request = request().withResponseContract(ResponseContract.predicate());
        for (String valid : List.of("true", "false", " true\n"))
            assertEquals(valid, NativeToolResponses.normalize(mapper, request, valid, List.of()));
        for (String invalid :
                List.of("True", "true because the task is done", "{\"result\":true}", ""))
            assertThrows(
                    ToolDocs.nonNullClass(ModelSchemaException.class),
                    () -> NativeToolResponses.normalize(mapper, request, invalid, List.of()));
        assertThrows(
                ToolDocs.nonNullClass(ModelSchemaException.class),
                () ->
                        NativeToolResponses.normalize(
                                mapper,
                                request,
                                "true",
                                List.of(
                                        new NativeToolResponses.Call(
                                                "read", mapper.createObjectNode(), "id"))));
    }

    @Test
    void mandatoryCompletionCannotEndWithTextOrMultipleCalls() {
        var request = request().withResponseContract(ResponseContract.completion("read", false));
        var call = new NativeToolResponses.Call("read", mapper.createObjectNode(), "id");
        assertEquals("", NativeToolResponses.normalize(mapper, request, "", List.of(call)));
        assertThrows(
                ToolDocs.nonNullClass(ModelSchemaException.class),
                () -> NativeToolResponses.normalize(mapper, request, "Finished", List.of()));
        assertThrows(
                ToolDocs.nonNullClass(ModelSchemaException.class),
                () -> NativeToolResponses.normalize(mapper, request, "Progress", List.of(call)));
        assertThrows(
                ToolDocs.nonNullClass(ModelSchemaException.class),
                () ->
                        NativeToolResponses.normalize(
                                mapper,
                                request,
                                "",
                                List.of(
                                        call,
                                        new NativeToolResponses.Call(
                                                "read", mapper.createObjectNode(), "id2"))));
        var finalOnly = request.withResponseContract(ResponseContract.completion("finish", true));
        assertThrows(
                ToolDocs.nonNullClass(ModelSchemaException.class),
                () -> NativeToolResponses.normalize(mapper, finalOnly, "", List.of(call)));
    }

    @Test
    void textGenerationKeepsOrdinaryJsonAsContentAndExcludesTools() {
        var request = request(List.of()).withResponseContract(ResponseContract.generation());
        assertEquals(
                "{\"summary\":\"done\"}",
                NativeToolResponses.normalize(
                        mapper, request, "{\"summary\":\"done\"}", List.of()));
        assertThrows(
                ToolDocs.nonNullClass(ModelSchemaException.class),
                () ->
                        NativeToolResponses.normalize(
                                mapper,
                                request,
                                "",
                                List.of(
                                        new NativeToolResponses.Call(
                                                "read", mapper.createObjectNode(), "id"))));
    }

    @Test
    void citationGenerationRequiresOneAnswerSubmissionWithNoAccompanyingText() {
        var answerTool =
                new ToolDefinition(
                        "answer_with_citations",
                        "Submit a cited answer",
                        Map.of("type", "object"),
                        List.of(),
                        ToolDocumentation.empty(),
                        List.of(),
                        List.of());
        var request =
                request(List.of(answerTool)).withResponseContract(ResponseContract.generation());
        var call =
                new NativeToolResponses.Call(
                        "answer_with_citations", mapper.createObjectNode(), "id");
        assertEquals("", NativeToolResponses.normalize(mapper, request, "", List.of(call)));
        for (String text : List.of("", "Plain answer", "{\"summary\":\"done\"}"))
            assertThrows(
                    ToolDocs.nonNullClass(ModelSchemaException.class),
                    () -> NativeToolResponses.normalize(mapper, request, text, List.of()));
        assertThrows(
                ToolDocs.nonNullClass(ModelSchemaException.class),
                () ->
                        NativeToolResponses.normalize(
                                mapper, request, "Accompanying text", List.of(call)));
        assertThrows(
                ToolDocs.nonNullClass(ModelSchemaException.class),
                () ->
                        NativeToolResponses.normalize(
                                mapper,
                                request,
                                "",
                                List.of(
                                        call,
                                        new NativeToolResponses.Call(
                                                "answer_with_citations",
                                                mapper.createObjectNode(),
                                                "id2"))));
    }

    @Test
    void wrapperUsesActualInvocationContractAndAvailableToolNames() {
        var original = request();
        var predicate = original.withResponseContract(ResponseContract.predicate());
        assertEquals(original.tools(), predicate.tools());
        assertEquals(original.messages(), predicate.messages());
        assertTrue(
                NativeToolResponses.prompt(ProviderTestPrompts.PROMPTS, predicate)
                        .contains("exactly true or false"));
        assertFalse(
                NativeToolResponses.prompt(ProviderTestPrompts.PROMPTS, predicate)
                        .contains("Invoke registered tools"));
        String ordinary = NativeToolResponses.prompt(ProviderTestPrompts.PROMPTS, original);
        assertTrue(ordinary.contains("[\"read\"]"));
        assertFalse(ordinary.contains("ask_user"));
        String completion =
                NativeToolResponses.prompt(
                        ProviderTestPrompts.PROMPTS,
                        original.withResponseContract(ResponseContract.completion("read", true)));
        assertTrue(completion.contains("Call `read` exactly once"));
        assertFalse(completion.contains("A tool call is not required"));
    }

    @Test
    void doesNotTreatEmbeddedProtocolExamplesAsAnExecutionChannel() {
        String explanation = "Example only: ```json\n{\"calls\":[]}\n```";
        assertDoesNotThrow(
                () ->
                        NativeToolResponses.normalize(
                                mapper,
                                request(),
                                explanation,
                                List.of(
                                        new NativeToolResponses.Call(
                                                "read", mapper.createObjectNode(), "id"))));
        assertEquals(
                "{\"calls\":[]}",
                NativeToolResponses.normalize(
                        mapper,
                        request(),
                        "{\"calls\":[]}",
                        List.of(
                                new NativeToolResponses.Call(
                                        "read", mapper.createObjectNode(), "id"))));
    }

    @Test
    void treatsJsonLookingTextAsDataNeverAsExecutableCalls() {
        for (String json :
                List.of(
                        "{\"calls\":[]}",
                        "{\"calls\":null}",
                        "{\"message\":\"ok\",\"calls\":[{\"tool_name\":\"read\",\"args\":{}}]}")) {
            var provider =
                    new AbstractLlmProvider(
                            mapper, mock(ToolDocs.nonNullClass(AuditLogger.class))) {
                        @Override
                        public boolean supports(@NonNull ProviderType type) {
                            return true;
                        }

                        @Override
                        public @NonNull String defaultBaseUrl() {
                            return "http://localhost";
                        }

                        @Override
                        protected @NonNull String providerName() {
                            return "test";
                        }

                        @Override
                        protected LlmClient.@NonNull RawCompletion invoke(
                                @NonNull ResolvedRequest request) {
                            return new LlmClient.RawCompletion("test", json);
                        }
                    };
            var response = provider.execute(new ResolvedRequest(request(), null, "unused"));
            assertEquals(json, response.message());
            assertNull(response.calls());
        }
        assertFalse(
                mapper.valueToTree(
                                new VetoResponse(
                                        null, List.of(new ToolCall("read", Map.of())), null))
                        .has("calls"));
    }

    private final @NonNull ObjectMapper mapper = new ObjectMapper();
    private final @NonNull ToolDefinition tool =
            new ToolDefinition(
                    "read",
                    "Read",
                    Map.of("type", "object"),
                    List.of(),
                    ToolDocumentation.empty(),
                    List.of(),
                    List.of());

    private @NonNull VetoRequest request() {
        return request(List.of(tool));
    }

    private @NonNull VetoRequest request(@NonNull List<ToolDefinition> tools) {
        return new VetoRequest(
                "System",
                "Read",
                tools,
                ProviderType.GEMINI,
                "test",
                "key",
                LlmOptions.defaults(),
                List.of(),
                null,
                null);
    }

    @Test
    void rejectsUnknownToolsDuplicateIdsAndMalformedArguments() {
        var args = mapper.createObjectNode();
        assertThrows(
                ToolDocs.nonNullClass(ModelSchemaException.class),
                () ->
                        NativeToolResponses.normalize(
                                mapper,
                                request(),
                                "",
                                List.of(new NativeToolResponses.Call("unknown", args, "a"))));
        assertThrows(
                ToolDocs.nonNullClass(ModelSchemaException.class),
                () ->
                        NativeToolResponses.normalize(
                                mapper,
                                request(),
                                "",
                                List.of(
                                        new NativeToolResponses.Call("read", args, "a"),
                                        new NativeToolResponses.Call("read", args, "a"))));
        for (String bad : List.of("[]", "null", "{", "{\"path\":1,\"path\":2}", "{} {}"))
            assertThrows(
                    ToolDocs.nonNullClass(ModelSchemaException.class),
                    () -> NativeToolResponses.arguments(mapper, bad));
    }

    @Test
    void trustedAdapterStateIsAttachedAfterParsingWithoutChangingArguments() {
        var state = new NativeToolState("test", "batch", "signed", 0);
        @NonNull AuditLogger logger = mock();
        var provider =
                new AbstractLlmProvider(mapper, logger) {
                    @Override
                    public boolean supports(@NonNull ProviderType type) {
                        return true;
                    }

                    @Override
                    public String defaultBaseUrl() {
                        return null;
                    }

                    @Override
                    protected @NonNull String providerName() {
                        return "test";
                    }

                    @Override
                    protected LlmClient.@NonNull RawCompletion invoke(
                            @NonNull ResolvedRequest request) {
                        return new LlmClient.RawCompletion(
                                "test",
                                "{}",
                                List.of(state),
                                List.of(new ToolCall("read", Map.of("path", "a"))));
                    }
                };
        var response = provider.execute(new ResolvedRequest(request(), null, "unused"));
        var calls = response.calls();
        if (calls == null) throw new AssertionError("Missing calls");
        assertEquals(state, calls.getFirst().nativeState());
        assertEquals(Map.of("path", "a"), calls.getFirst().args());
    }
}
