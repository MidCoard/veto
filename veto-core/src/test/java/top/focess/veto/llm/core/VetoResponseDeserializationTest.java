package top.focess.veto.llm.core;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.veto.api.agent.tool.ToolDocs;
import top.focess.veto.api.llm.ToolCall;
import top.focess.veto.api.llm.VetoResponse;

/**
 * Guards the snake_case binding of the universal {@link VetoResponse} ({@code veto_pulse}) schema —
 * the shared contract. Verifies {@code tool_name} / {@code guide} populate the record correctly.
 */
class VetoResponseDeserializationTest {
    private final @NonNull ObjectMapper mapper = new ObjectMapper();

    @Test
    void toolCallsHaveStableIdsFromConstructionAndDeserialization() throws Exception {
        ToolCall created = new ToolCall("think", Map.of());
        assertTrue(created.callId().startsWith("call_"));
        assertNotEquals(created.callId(), new ToolCall("think", Map.of()).callId());
        for (String idField : List.of("", ",\"call_id\":null")) {
            ToolCall parsed =
                    mapper.readValue(
                            "{\"tool_name\":\"think\",\"args\":{}" + idField + "}",
                            ToolDocs.nonNullClass(ToolCall.class));
            assertTrue(parsed.callId().startsWith("call_"));
        }
        ToolCall restored =
                mapper.readValue(
                        mapper.writeValueAsString(created), ToolDocs.nonNullClass(ToolCall.class));
        assertEquals(created.callId(), restored.callId());
    }

    @Test
    void jsonCannotSupplyNativeCalls() throws Exception {
        String json =
                "{\"thought\":\"t\",\"calls\":[{\"tool_name\":\"list_files\",\"args\":{\"path\":\"/x\"}}],"
                        + "\"message\":null}";
        VetoResponse response = mapper.readValue(json, ToolDocs.nonNullClass(VetoResponse.class));
        assertEquals("t", response.thought());
        assertFalse(response.hasCalls(), "JSON cannot construct adapter-owned native calls");
    }

    @Test
    void bindsStopNoCalls() throws Exception {
        String json = "{\"message\":\"done\"}";
        VetoResponse response = mapper.readValue(json, ToolDocs.nonNullClass(VetoResponse.class));
        assertFalse(response.hasCalls());
        assertEquals("done", response.message());
    }

    private static @NonNull List<@NonNull ToolCall> requireCalls(List<@NonNull ToolCall> calls) {
        if (calls != null) {
            return calls;
        }
        throw new AssertionError("calls should be present");
    }

    @Test
    void planIsNotAResponseFieldAndNullFieldsAreNotSerialized() throws Exception {
        assertThrows(
                ToolDocs.nonNullClass(JsonProcessingException.class),
                () ->
                        mapper.readValue(
                                "{\"guide\":{\"actions\":[]}}",
                                ToolDocs.nonNullClass(VetoResponse.class)));
        assertEquals(
                "{\"message\":\"done\"}",
                mapper.writeValueAsString(new VetoResponse(null, null, "done")));
    }

    @Test
    void rejectsLegacyFeatureSwitchAndIncompleteGuideAtDeserialization() {
        for (String json :
                List.of(
                        "{\"features\":{\"guided\":true}}",
                        "{\"actions\":[]}",
                        "{\"guide\":true}",
                        "{\"guide\":{}}")) {
            assertThrows(
                    ToolDocs.nonNullClass(JsonProcessingException.class),
                    () ->
                            mapper.readerFor(ToolDocs.nonNullClass(VetoResponse.class))
                                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                                    .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                                    .readValue(json),
                    json);
        }
    }
}
