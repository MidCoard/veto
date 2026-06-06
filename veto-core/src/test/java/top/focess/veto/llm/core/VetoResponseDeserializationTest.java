package top.focess.veto.llm.core;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/** Guards the snake_case binding bug: tool_name / is_finished must populate the records. */
class VetoResponseDeserializationTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void bindsSnakeCaseToolCall() throws Exception {
        String json =
                "{\"thought\":\"t\",\"call\":{\"tool_name\":\"list_files\",\"args\":{\"path\":\"/x\"}},\"is_finished\":false}";
        VetoResponse response = mapper.readValue(json, VetoResponse.class);
        assertEquals("t", response.thought());
        assertFalse(response.isFinished());
        assertNotNull(response.call());
        assertEquals("list_files", response.call().toolName());
        assertEquals("/x", response.call().args().get("path"));
    }

    @Test
    void bindsFinishedNullToolCall() throws Exception {
        String json =
                "{\"thought\":\"done\",\"call\":{\"tool_name\":null,\"args\":{}},\"is_finished\":true}";
        VetoResponse response = mapper.readValue(json, VetoResponse.class);
        assertTrue(response.isFinished());
        assertNull(response.call().toolName());
    }
}
