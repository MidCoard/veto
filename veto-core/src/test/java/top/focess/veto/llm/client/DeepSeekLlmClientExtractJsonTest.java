package top.focess.veto.llm.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import top.focess.veto.agent.translation.CapabilityTranslator;

/**
 * Unit tests for {@link DeepSeekLlmClient#extractJson} — the salvage parser for DeepSeek's
 * probabilistically schema-compliant Responses API output. Covers the two failures observed in
 * production: prose + pretty-printed JSON (no compact {@code {"` anchor), and several top-level
 * veto_pulse objects in one response (a message object followed by a calls object - returning the
 * first silently dropped the calls).
 */
class DeepSeekLlmClientExtractJsonTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private final DeepSeekLlmClient client =
            new DeepSeekLlmClient(
                    "", "test-key", "DeepSeek", mapper, mock(CapabilityTranslator.class));

    @Test
    void cleanSingleObjectPassesThrough() throws Exception {
        String json = "{\"message\":\"hi\",\"features\":{\"guided\":false}}";
        JsonNode node = mapper.readTree(client.extractJson(json));
        assertEquals("hi", node.get("message").asText());
    }

    @Test
    void prosePrefixedPrettyJsonIsExtracted() throws Exception {
        String content =
                "I'll create the file now.\n\n{\n  \"thought\": \"t\",\n  \"calls\": [\n    {\n"
                        + "      \"tool_name\": \"write_to_file\",\n      \"args\": {}\n    }\n"
                        + "  ]\n}";
        JsonNode node = mapper.readTree(client.extractJson(content));
        assertEquals("t", node.get("thought").asText());
        assertEquals(1, node.get("calls").size());
        assertEquals("write_to_file", node.get("calls").get(0).get("tool_name").asText());
    }

    @Test
    void multipleTopLevelObjectsAreMerged() throws Exception {
        // The exact production shape from 2026-08-11: a message object, a blank line, then a
        // calls object. Jackson's readValue would parse the first and silently drop the calls.
        String content =
                "{\"message\":\"Executing...\",\"features\":{\"guided\":false}}\n\n"
                        + "{\"calls\":[{\"tool_name\":\"run_command\",\"args\":{}}],"
                        + "\"features\":{\"guided\":false}}";
        JsonNode node = mapper.readTree(client.extractJson(content));
        assertEquals("Executing...", node.get("message").asText());
        assertEquals(1, node.get("calls").size());
        assertEquals("run_command", node.get("calls").get(0).get("tool_name").asText());
    }

    @Test
    void callsArraysConcatenateAcrossObjects() throws Exception {
        String content =
                "{\"calls\":[{\"tool_name\":\"view_file\",\"args\":{}}]}\n"
                        + "{\"calls\":[{\"tool_name\":\"write_to_file\",\"args\":{}}]}";
        JsonNode node = mapper.readTree(client.extractJson(content));
        assertEquals(2, node.get("calls").size());
        assertEquals("view_file", node.get("calls").get(0).get("tool_name").asText());
        assertEquals("write_to_file", node.get("calls").get(1).get("tool_name").asText());
    }

    @Test
    void markdownFencedJsonIsUnwrapped() throws Exception {
        String content = "```json\n{\"message\":\"fenced\"}\n```";
        JsonNode node = mapper.readTree(client.extractJson(content));
        assertEquals("fenced", node.get("message").asText());
    }

    @Test
    void bracesInsideStringsDoNotSkewDepth() throws Exception {
        String content = "prose {\n{\"message\":\"use } and { carefully\",\"calls\":[]}\ntrailing";
        JsonNode node = mapper.readTree(client.extractJson(content));
        assertEquals("use } and { carefully", node.get("message").asText());
    }

    @Test
    void plainTextWithoutJsonIsReturnedUnchanged() {
        String content = "I'll read the build file and report back.";
        assertEquals(content, client.extractJson(content));
        assertTrue(client.extractJson(content).indexOf('{') < 0);
    }
}
