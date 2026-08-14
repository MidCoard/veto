package top.focess.veto.llm.core;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.veto.agent.mcp.ToolDocs;

/**
 * Guards the snake_case binding of the universal {@link VetoResponse} ({@code veto_pulse}) schema —
 * the shared contract. Verifies {@code tool_name} / {@code features} populate the record correctly.
 */
class VetoResponseDeserializationTest {
    private final @NonNull ObjectMapper mapper = new ObjectMapper();

    @Test
    void bindsAutonomousCall() throws Exception {
        String json =
                "{\"thought\":\"t\",\"calls\":[{\"tool_name\":\"list_files\",\"args\":{\"path\":\"/x\"}}],"
                        + "\"features\":{\"guided\":false}}";
        VetoResponse response = mapper.readValue(json, ToolDocs.nonNullClass(VetoResponse.class));
        assertEquals("t", response.thought());
        assertTrue(response.hasCalls());
        List<@NonNull ToolCall> calls = requireCalls(response.calls());
        assertEquals(1, calls.size());
        assertEquals("list_files", calls.get(0).toolName());
        assertEquals("/x", calls.get(0).args().get("path"));
        VetoResponse.@NonNull Features features = requireFeatures(response.features());
        assertFalse(features.guided());
    }

    @Test
    void bindsStopNoCalls() throws Exception {
        String json = "{\"message\":\"done\",\"features\":{\"guided\":false}}";
        VetoResponse response = mapper.readValue(json, ToolDocs.nonNullClass(VetoResponse.class));
        assertFalse(response.hasCalls());
        assertEquals("done", response.message());
        requireFeatures(response.features());
    }

    private static @NonNull List<@NonNull ToolCall> requireCalls(List<@NonNull ToolCall> calls) {
        if (calls != null) {
            return calls;
        }
        throw new AssertionError("calls should be present");
    }

    private static VetoResponse.@NonNull Features requireFeatures(VetoResponse.Features features) {
        if (features != null) {
            return features;
        }
        throw new AssertionError("features should be present");
    }
}
