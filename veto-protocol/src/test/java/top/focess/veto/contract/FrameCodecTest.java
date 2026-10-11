package top.focess.veto.contract;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import top.focess.veto.api.plugin.Scope;

class FrameCodecTest {

    @Test
    void errorUsesTheSameScopeValuesAsPluginOperations() throws Exception {
        var owner = UUID.randomUUID();
        var session = UUID.randomUUID().toString();
        var unscoped = Frame.Error.ofError("connection");
        var unscopedJson = FrameCodec.encodeString(unscoped);
        assertEquals(unscoped, FrameCodec.decode(unscopedJson));
        assertTrue(new ObjectMapper().readTree(unscopedJson).path("scope").isNull());
        Scope[] scopes = {
            new Scope.GlobalScope(),
            new Scope.UserScope(owner),
            new Scope.SessionScope(owner, session),
            new Scope.AgentScope(owner, session, "agent")
        };
        for (var scope : scopes) {
            var frame = new Frame.Error(scope, null, null, "failed", 0);
            String json = FrameCodec.encodeString(frame);
            assertEquals(frame, FrameCodec.decode(json));
            var tree = new ObjectMapper().readTree(json);
            assertFalse(tree.has("sessionId"));
            assertFalse(tree.has("agentId"));
            assertTrue(tree.has("scope"));
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"type\":\"session\",\"userId\":null,\"session\":\"s\"}",
                "{\"type\":\"agent\",\"userId\":\"00000000-0000-0000-0000-000000000010\",\"agent\":\"a\"}",
                "{\"type\":\"agent\",\"userId\":\"00000000-0000-0000-0000-000000000010\",\"session\":\"s\",\"agent\":null}"
            })
    void scopedErrorRejectsMissingRequiredIdentity(@NonNull String scope) {
        assertNull(
                FrameCodec.decode(
                        "{\"type\":\"error\",\"scope\":"
                                + scope
                                + ",\"content\":\"failed\",\"seq\":0}"));
    }

    @Test
    void everyDeltaPayloadPreservesItsTypedValueAcrossBytesAndText() throws Exception {
        var args = JsonNodeFactory.instance.objectNode();
        args.put("中文", "提示");
        args.putArray("items").add(1).add(true).addNull();
        Delta.Data[] updates = {
            new Delta.Message("command", null),
            new Delta.Notice("notice"),
            new Delta.Thought("thought", 1),
            new Delta.Message("message", 2),
            new Delta.ToolCall("call", "tool", args, 3),
            new Delta.ToolResult("call", "result", true, 4),
            new Delta.RecordUpdated(5),
            new Delta.SessionInvalidated(List.of("execution")),
            new Delta.PluginEvent("topic", args),
            new Delta.BreakerTripped("limit", 20, 6),
        };
        for (var update : updates) {
            var original =
                    new Delta(
                            new Scope.AgentScope(
                                    UUID.fromString("00000000-0000-0000-0000-000000000010"),
                                    (UUID.randomUUID()).toString(),
                                    "agent"),
                            update);
            var bytes = FrameCodec.encode(original);
            var text = FrameCodec.encodeString(original);
            assertArrayEquals(text.getBytes(StandardCharsets.UTF_8), bytes);
            assertEquals(original, FrameCodec.decode(bytes));
            assertEquals(original, FrameCodec.decode(text));
            var envelope = new ObjectMapper().readTree(text);
            assertEquals("delta", envelope.path("type").asText());
            assertTrue(envelope.path("data").path("type").isTextual());
            assertFalse(envelope.has("attrs"));
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"type\":\"thought\",\"turnNumber\":1}",
                "{\"type\":\"thought\",\"text\":\"x\"}",
                "{\"type\":\"tool_result\",\"text\":\"x\",\"turnNumber\":1}",
                "{\"type\":\"tool_call\",\"tool\":\"run\",\"args\":null,\"turnNumber\":1}",
                "{\"type\":\"unknown\"}"
            })
    void invalidDeltaPayloadsAreRejected(@NonNull String data) {
        String payload =
                "{\"type\":\"delta\",\"sessionId\":null,\"agentId\":null,"
                        + "\"sequence\":1,\"emittedAt\":\"2026-10-12T00:00:00Z\",\"data\":"
                        + data
                        + "}";
        assertNull(FrameCodec.decode(payload));
    }

    @Test
    void textAndByteTransportsUseTheSameWireContract() {
        var heartbeat = new Frame.Heartbeat();
        assertEquals("{\"type\":\"heartbeat\"}", FrameCodec.encodeString(heartbeat));
        Frame original = new Frame.Done("完成");
        assertArrayEquals(
                FrameCodec.encodeString(original).getBytes(StandardCharsets.UTF_8),
                FrameCodec.encode(original));
        assertEquals(original, FrameCodec.decode(FrameCodec.encode(original)));
    }

    @Test
    void groupSpecificFramesAreNotCoreProtocolMessages() {
        assertNull(FrameCodec.decode("{\"type\":\"dag.payload\",\"data\":{}}"));
        assertNull(
                FrameCodec.decode(
                        "{\"type\":\"dag.received\",\"taskType\":\"compile\",\"seq\":1,\"timestamp\":\"2026-10-12T00:00:00Z\"}"));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"type\":\"heartbeat\",\"type\":\"bye\"}",
                "{\"type\":\"heartbeat\"} {}",
                "{\"type\":\"heartbeat\"} trailing",
                "{broken",
                "null",
                ""
            })
    void malformedInputIsRejectedByTextAndByteDecoders(@NonNull String payload) {
        assertNull(FrameCodec.decode(payload));
        assertNull(FrameCodec.decode(payload.getBytes(StandardCharsets.UTF_8)));
    }
}
