package top.focess.veto.agent.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;

class ToolEngineRemoteContentTest {

    private final @NonNull ObjectMapper mapper = new ObjectMapper();

    @Test
    void emptyRemoteErrorBecomesActionableErrorEnvelope() throws Exception {
        JsonNode result =
                mapper.readTree(
                        "{\"content\":[{\"type\":\"text\",\"text\":\"\"}],\"isError\":true}");

        String content = ToolEngineImpl.remoteContent(result);

        JsonNode envelope = mapper.readTree(content);
        assertEquals("error", envelope.path("status").asText());
        assertTrue(envelope.path("error").asText().contains("empty text content"));
        assertTrue(envelope.path("error").asText().contains("isError"));
    }

    @Test
    void nonEmptyRemoteTextRemainsPlainContent() throws Exception {
        JsonNode result =
                mapper.readTree(
                        "{\"content\":[{\"type\":\"text\",\"text\":\"calendar unavailable\"}],\"isError\":true}");

        assertEquals("calendar unavailable", ToolEngineImpl.remoteContent(result));
    }
}
