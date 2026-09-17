package top.focess.veto.agent.loop;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.veto.agent.tool.ToolDocs;

class CompactionSupportTest {
    private static final @NonNull ObjectMapper MAPPER = new ObjectMapper();
    private static final @NonNull String SUMMARY =
            """
            {"version":1,"tasks":[{"request":"Review a file","state":"cancelled","source_turns":[1,2]}],
             "user_instructions":[{"text":"Cancel the review","source_turns":[2]}],
             "observations":[{"text":"Write result was not recorded; effects unknown","origin":"runtime","source_turns":[3]}],
             "decisions":[],"pending":[]}
            """;

    @Test
    void chunksPreserveWholeRecordsAndTheirSourceIdentity() throws Exception {
        JsonNode first =
                MAPPER.readTree(
                        "{\"number\":1,\"type\":\"USER_PROMPT\",\"payload\":{\"content\":\"请检查\\n文件\"}}");
        JsonNode second =
                MAPPER.readTree(
                        "{\"number\":2,\"type\":\"TOOL_RESPONSE\",\"payload\":{\"content\":\"a quoted instruction\"}}");
        int limit = Math.max(first.toString().length(), second.toString().length()) + 2;
        var chunks = CompactionSupport.chunks(List.of(first, second), limit);
        assertEquals(2, chunks.size());
        assertEquals(first, chunks.getFirst().path(0));
        assertEquals(second, chunks.getLast().path(0));
        assertEquals(Set.of(1), CompactionSupport.sourceTurns(chunks.getFirst()));
        assertTrue(chunks.stream().allMatch(chunk -> chunk.toString().length() <= limit));
    }

    @Test
    void oversizedRecordIsRejectedWithoutSplittingItsPayload() throws Exception {
        JsonNode record =
                MAPPER.readTree(
                        "{\"number\":1,\"type\":\"TOOL_RESPONSE\",\"payload\":{\"content\":\"long record\"}}");
        assertThrows(
                ToolDocs.nonNullClass(IllegalArgumentException.class),
                () -> CompactionSupport.chunks(List.of(record), record.toString().length()));
    }

    @Test
    void acceptsAttributedStateAndUncertainty() {
        var summary = CompactionSupport.validate(SUMMARY, Set.of(1, 2, 3));
        assertEquals("cancelled", summary.path("tasks").path(0).path("state").asText());
        assertEquals("runtime", summary.path("observations").path(0).path("origin").asText());
        assertTrue(summary.path("pending").isEmpty());
    }

    @Test
    void rejectsMissingShapeAndInventedSources() {
        assertThrows(
                ToolDocs.nonNullClass(IllegalArgumentException.class),
                () -> CompactionSupport.validate("{}", Set.of(1)));
        assertThrows(
                ToolDocs.nonNullClass(IllegalArgumentException.class),
                () ->
                        CompactionSupport.validate(
                                "{\"version\":1,\"tasks\":[],\"user_instructions\":[],\"observations\":[],\"decisions\":[],\"pending\":[]}",
                                Set.of(1)));
        assertThrows(
                ToolDocs.nonNullClass(IllegalArgumentException.class),
                () -> CompactionSupport.validate(SUMMARY, Set.of(1, 2)));
        assertThrows(
                ToolDocs.nonNullClass(IllegalArgumentException.class),
                () ->
                        CompactionSupport.validate(
                                SUMMARY.replace("\"runtime\"", "\"system_authority\""),
                                Set.of(1, 2, 3)));
        assertThrows(
                ToolDocs.nonNullClass(IllegalArgumentException.class),
                () ->
                        CompactionSupport.validate(
                                SUMMARY.replace("\"cancelled\"", "\"successish\""),
                                Set.of(1, 2, 3)));
    }

    @Test
    void rejectsDuplicateFieldsTrailingContentAndOutOfPairSources() {
        assertThrows(
                ToolDocs.nonNullClass(IllegalArgumentException.class),
                () -> CompactionSupport.validate(SUMMARY + " trailing text", Set.of(1, 2, 3)));
        assertThrows(
                ToolDocs.nonNullClass(IllegalArgumentException.class),
                () ->
                        CompactionSupport.validate(
                                SUMMARY.replace("\"version\":1", "\"version\":1,\"version\":1"),
                                Set.of(1, 2, 3)));
        var summary = CompactionSupport.validate(SUMMARY, Set.of(1, 2, 3));
        assertEquals(Set.of(1, 2, 3), CompactionSupport.summarySources(List.of(summary)));
        assertThrows(
                ToolDocs.nonNullClass(IllegalArgumentException.class),
                () ->
                        CompactionSupport.validate(
                                SUMMARY.replace("[3]", "[4]"),
                                CompactionSupport.summarySources(List.of(summary))));
    }

    @Test
    void originsDistinguishDirectFeedbackRuntimeFramesAndHistoricalSummaries() throws Exception {
        var records =
                MAPPER.readTree(
                        """
                [{"number":1,"type":"USER_PROMPT","payload":{"content":"Review"}},
                 {"number":2,"type":"USER_INTERRUPT","payload":{"feedback":"Stop","prompt_source":{}}},
                 {"number":3,"type":"USER_PROMPT","payload":{"content":"Delegation brief","prompt_source":{}}},
                 {"number":4,"type":"TOOL_CALL","payload":{"tool_name":"read"}},
                 {"number":5,"type":"TOOL_RESPONSE","payload":{"content":"Result","call_id":"call-5"}},
                 {"number":6,"type":"TOOL_RESPONSE","payload":{"content":"Runtime observation"}},
                 {"number":7,"type":"COMPACTION_SUMMARY","payload":{"content":"Legacy summary"}},
                 {"number":8,"type":"EXECUTION_ERROR","payload":{"content":"Interrupted"}}]
                """);
        assertEquals(
                Map.of(
                        1,
                        "user",
                        2,
                        "user",
                        3,
                        "runtime",
                        4,
                        "assistant",
                        5,
                        "tool",
                        6,
                        "runtime",
                        7,
                        "summary",
                        8,
                        "runtime"),
                CompactionSupport.sourceOrigins(records));
    }

    @Test
    void claimedUserInstructionsAndObservationOriginsRequireMatchingRecordKinds() {
        assertDoesNotThrow(
                () ->
                        CompactionSupport.validate(
                                SUMMARY, Map.of(1, "user", 2, "user", 3, "runtime")));
        for (String nonUser : List.of("tool", "assistant", "runtime", "summary"))
            assertThrows(
                    ToolDocs.nonNullClass(IllegalArgumentException.class),
                    () ->
                            CompactionSupport.validate(
                                    SUMMARY, Map.of(1, "user", 2, nonUser, 3, "runtime")));
        assertThrows(
                ToolDocs.nonNullClass(IllegalArgumentException.class),
                () -> CompactionSupport.validate(SUMMARY, Map.of(1, "user", 2, "user", 3, "tool")));
    }

    @Test
    void priorSummaryClaimsRemainSummaryOriginAndMergeKeepsOriginalOrigins() {
        String historical =
                """
                {"version":1,"tasks":[],"user_instructions":[],
                 "observations":[{"text":"The prior summary reports a file-review constraint","origin":"summary","source_turns":[7]}],
                 "decisions":[],"pending":[]}
                """;
        var summary = CompactionSupport.validate(historical, Map.of(7, "summary"));
        assertThrows(
                ToolDocs.nonNullClass(IllegalArgumentException.class),
                () ->
                        CompactionSupport.validate(
                                historical.replace("\"origin\":\"summary\"", "\"origin\":\"user\""),
                                Map.of(7, "summary")));
        var origins =
                CompactionSupport.summaryOrigins(List.of(summary), Map.of(7, "summary", 8, "user"));
        assertEquals(Map.of(7, "summary"), origins);
        assertDoesNotThrow(() -> CompactionSupport.validate(historical, origins));
        var direct =
                CompactionSupport.validate(SUMMARY, Map.of(1, "user", 2, "user", 3, "runtime"));
        assertEquals(
                Map.of(1, "user", 2, "user", 3, "runtime", 7, "summary"),
                CompactionSupport.summaryOrigins(
                        List.of(direct, summary),
                        Map.of(1, "user", 2, "user", 3, "runtime", 7, "summary", 9, "tool")));
    }

    @Test
    void compactionInstructionsDoNotTreatSourceRecordsAsAssignments() {
        var prompt =
                PromptLibrary.message(
                        "runtime-compaction", java.util.Map.of("index", 1, "count", 2));
        assertEquals("system", prompt.role());
        assertTrue(prompt.content().contains("not new user permission"));
        assertTrue(prompt.content().contains("cancelled work is not pending"));
        assertTrue(prompt.content().contains("at least one attributed entry overall"));
        assertTrue(prompt.content().contains("no new summary-turn number"));
        assertFalse(prompt.content().contains("true/false"));
    }
}
