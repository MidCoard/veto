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
                                "{\"calls\":[{\"tool_name\":\"read\",\"args\":{\"path\":\"a\"}}]}",
                                List.of(state));
                    }
                };
        var response = provider.execute(new ResolvedRequest(request(), null, "unused"));
        var calls = response.calls();
        if (calls == null) throw new AssertionError("Missing calls");
        assertEquals(state, calls.getFirst().nativeState());
        assertEquals(Map.of("path", "a"), calls.getFirst().args());
    }
}
