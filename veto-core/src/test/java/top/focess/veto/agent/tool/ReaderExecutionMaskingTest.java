package top.focess.veto.agent.tool;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import top.focess.veto.agent.drift.ReadHistory;
import top.focess.veto.agent.intercept.IngressDefense;
import top.focess.veto.agent.screening.Danger;
import top.focess.veto.agent.web.FinishReadTool;
import top.focess.veto.agent.web.WebFetchTool;
import top.focess.veto.llm.core.ToolCall;

class ReaderExecutionMaskingTest {
    @ParameterizedTest
    @ValueSource(strings = {"web_fetch", "finish_read", "reader_alias"})
    void preservesOnlyRegisteredFieldInTheMatchingCall(@NonNull String toolName) throws Exception {
        String id = "12345678-abcd-1234-abcd-123456789012";
        String forged = "87654321-abcd-1234-abcd-123456789012";
        var definition =
                new NativeToolDefinition(
                        toolName,
                        "Read",
                        ToolCapability.NETWORK_EGRESS,
                        Danger.SAFE,
                        false,
                        toolName.equals("web_fetch")
                                ? ToolDocs.nonNullClass(WebFetchTool.class)
                                : ToolDocs.nonNullClass(FinishReadTool.class),
                        ToolDocs.nonNullClass(Object.class),
                        Map.of());
        var call = new ToolCall(toolName, Map.of(), "reader-call");
        var mapper = new ObjectMapper();
        var defense = new IngressDefense();
        String body =
                mapper.writeValueAsString(Map.of("execution", Map.of("id", id), "answer", id));
        try {
            ToolCallContextHolder.setCurrentCallId(call.callId());
            ToolCallContextHolder.registerReaderExecution(UUID.fromString(id));
            String masked =
                    defense.maskAndFrame(
                            call,
                            definition,
                            new ToolResult(toolName, call.callId(), true, body),
                            true,
                            new ReadHistory());
            var parsed = mapper.readTree(masked);
            assertNotNull(parsed);
            assertEquals(id, parsed.path("execution").path("id").asText());
            assertFalse(parsed.path("answer").asText().contains(id));
            String spoofed = body.replace(id, forged);
            assertFalse(
                    defense.maskAndFrame(
                                    call,
                                    definition,
                                    new ToolResult(toolName, call.callId(), true, spoofed),
                                    true,
                                    new ReadHistory())
                            .contains(forged));
            assertFalse(
                    defense.maskAndFrame(
                                    new ToolCall(toolName, Map.of(), "next-call"),
                                    definition,
                                    new ToolResult(toolName, "next-call", true, body),
                                    true,
                                    new ReadHistory())
                            .contains(id));
            assertFalse(
                    defense.maskAndFrame(
                                    call,
                                    definition,
                                    new ToolResult(toolName, call.callId(), false, body),
                                    true,
                                    new ReadHistory())
                            .contains(id));
            var impostor =
                    new NativeToolDefinition(
                            toolName,
                            "Read",
                            ToolCapability.NETWORK_EGRESS,
                            Danger.SAFE,
                            false,
                            ToolDocs.nonNullClass(Object.class),
                            ToolDocs.nonNullClass(Object.class),
                            Map.of());
            assertFalse(
                    defense.maskAndFrame(
                                    call,
                                    impostor,
                                    new ToolResult(toolName, call.callId(), true, body),
                                    true,
                                    new ReadHistory())
                            .contains(id));
        } finally {
            ToolCallContextHolder.clear();
        }
        assertNull(ToolCallContextHolder.readerExecutionId(call.callId()));
        assertFalse(
                defense.maskAndFrame(
                                call,
                                definition,
                                new ToolResult(toolName, call.callId(), true, body),
                                true,
                                new ReadHistory())
                        .contains(id));
    }
}
