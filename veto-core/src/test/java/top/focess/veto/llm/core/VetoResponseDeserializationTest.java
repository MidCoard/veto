package top.focess.veto.llm.core;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * Guards the snake_case binding of the universal {@link VetoResponse} ({@code veto_pulse}) schema —
 * the shared contract. Verifies {@code tool_name} / {@code is_finished} / {@code features} populate
 * the record correctly.
 */
class VetoResponseDeserializationTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void bindsAutonomousCall() throws Exception {
        String json =
                "{\"thought\":\"t\",\"calls\":[{\"tool_name\":\"list_files\",\"args\":{\"path\":\"/x\"}}],"
                        + "\"is_finished\":false,\"features\":{\"guided\":false,\"thought\":true}}";
        VetoResponse response = mapper.readValue(json, VetoResponse.class);
        assertEquals("t", response.thought());
        assertFalse(response.isFinished());
        assertTrue(response.hasCalls());
        assertEquals(1, response.calls().size());
        assertEquals("list_files", response.calls().get(0).toolName());
        assertEquals("/x", response.calls().get(0).args().get("path"));
        assertNotNull(response.features());
        assertFalse(response.features().guided());
        assertTrue(response.features().thought());
    }

    @Test
    void bindsFinishedNoCalls() throws Exception {
        String json =
                "{\"message\":\"done\",\"is_finished\":true,\"features\":{\"guided\":false,\"thought\":false}}";
        VetoResponse response = mapper.readValue(json, VetoResponse.class);
        assertTrue(response.isFinished());
        assertFalse(response.hasCalls());
        assertEquals("done", response.message());
        assertNotNull(response.features());
    }
}
