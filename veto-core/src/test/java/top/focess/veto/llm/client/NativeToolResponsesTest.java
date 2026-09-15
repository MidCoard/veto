package top.focess.veto.llm.client;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.veto.agent.tool.ToolDocs;
import top.focess.veto.agent.tool.ToolDocumentation;
import top.focess.veto.agent.translation.VetoCapabilityTranslator;
import top.focess.veto.llm.core.*;
import top.focess.veto.llm.exceptions.ModelSchemaException;
import top.focess.veto.llm.provider.AbstractLlmProvider;
import top.focess.veto.observability.AuditLogger;

class NativeToolResponsesTest {

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
                                        null, List.of(new ToolCall("read", Map.of())), null, null))
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
        return new VetoRequest(
                "System",
                "Read",
                List.of(tool),
                ProviderType.GEMINI,
                "test",
                "key",
                LlmOptions.defaults(),
                List.of(),
                new VetoCapabilityTranslator().vetoResponseSchema(true, List.of(tool)),
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
