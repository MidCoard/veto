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
    void emptyRemoteErrorBecomesActionableSpecialPlaintext() throws Exception {
        JsonNode result =
                mapper.readTree(
                        "{\"content\":[{\"type\":\"text\",\"text\":\"\"}],\"isError\":true}");

        String content = ToolEngineImpl.remoteContent(result);

        assertTrue(content.contains("empty text content"));
        assertTrue(content.contains("isError"));
        assertTrue(!content.stripLeading().startsWith("{"), content);
    }

    @Test
    void nonEmptyRemoteTextRemainsPlainContent() throws Exception {
        JsonNode result =
                mapper.readTree(
                        "{\"content\":[{\"type\":\"text\",\"text\":\"calendar unavailable\"}],\"isError\":true}");

        assertEquals("calendar unavailable", ToolEngineImpl.remoteContent(result));
    }
}
