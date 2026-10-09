package top.focess.veto.contract;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ProtocolJsonTest {

    @Test
    @SuppressWarnings(
            "ConstantValue") // Checker applies the nullable default to synthetic values().
    void everyStreamingKindPreservesItsCompleteValueAcrossBytesAndText() throws Exception {
        var attrs = JsonNodeFactory.instance.objectNode();
        attrs.put("中文", "提示");
        attrs.putArray("items").add(1).add(true).addNull();
        var kinds = EventFrame.Kind.values();
        if (kinds == null) {
            throw new AssertionError("Enum values are unavailable");
        }
        for (var kind : kinds) {
            var original =
                    EventFrame.builder()
                            .sessionId(UUID.randomUUID())
                            .sequence(42)
                            .kind(kind)
                            .text("消息 /compact")
                            .attr("detail", attrs)
                            .attr("success", true)
                            .build();
            var bytes = ProtocolJson.encode(original);
            var text = ProtocolJson.encodeString(original);
            assertArrayEquals(text.getBytes(StandardCharsets.UTF_8), bytes);
            assertEquals(original, ProtocolJson.decode(bytes, new TypeReference<EventFrame>() {}));
            assertEquals(original, ProtocolJson.decode(text, new TypeReference<EventFrame>() {}));
            var envelope = ProtocolJson.readTree(text);
            assertEquals(original.emittedAt().toString(), envelope.path("emittedAt").asText());
            assertEquals(kind.name(), envelope.path("kind").asText());
            assertEquals(attrs, envelope.path("attrs").path("detail"));
        }
    }

    @Test
    void websocketAndIpcUseTheSameEncodingMiddleware() throws Exception {
        var heartbeat = new Frame.Heartbeat(9);
        assertEquals("{\"type\":\"heartbeat\",\"seq\":9}", ProtocolJson.encodeString(heartbeat));
        assertEquals(
                heartbeat,
                ProtocolJson.decode(
                        ProtocolJson.encode(heartbeat), new TypeReference<Frame.Heartbeat>() {}));
        Frame original = new Frame.Done(Map.of("username", "alice"), "完成");
        assertArrayEquals(ProtocolJson.encode(original), FrameCodec.encode(original));
        assertEquals(ProtocolJson.encodeString(original), FrameCodec.encodeString(original));
        assertEquals(original, FrameCodec.decode(ProtocolJson.encode(original)));
        assertEquals(
                original,
                ProtocolJson.decode(FrameCodec.encode(original), new TypeReference<Frame>() {}));
    }

    @Test
    void dagRoundTripPreservesLifecycleAndImmutableData() throws Exception {
        var original =
                DAGPayload.builder()
                        .id("task-1")
                        .taskType("compile")
                        .parameter("source", "测试.java")
                        .dependency("task-0")
                        .sourceComponent("terminal")
                        .targetComponent("backend")
                        .build()
                        .withStatus(DAGPayload.DAGPayloadStatus.RUNNING);
        var parsed =
                ProtocolJson.decode(
                        ProtocolJson.encode(original), new TypeReference<DAGPayload>() {});
        assertEquals(original.getId(), parsed.getId());
        assertEquals(original.getTaskType(), parsed.getTaskType());
        assertEquals(original.getParameters(), parsed.getParameters());
        assertEquals(original.getDependencies(), parsed.getDependencies());
        assertEquals(original.getStatus(), parsed.getStatus());
        assertEquals(original.getCreatedAt(), parsed.getCreatedAt());
        assertEquals(original.getUpdatedAt(), parsed.getUpdatedAt());
        assertEquals(original.getSourceComponent(), parsed.getSourceComponent());
        assertEquals(original.getTargetComponent(), parsed.getTargetComponent());
        assertThrows(
                UnsupportedOperationException.class,
                () -> parsed.getParameters().put("new", "value"));
        assertThrows(
                UnsupportedOperationException.class, () -> parsed.getDependencies().add("new"));
        assertThrows(
                IOException.class,
                () -> ProtocolJson.decode("{}", new TypeReference<DAGPayload>() {}));
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
    void malformedInputIsRejectedBySharedAndIpcDecoders(@NonNull String payload) {
        assertThrows(IOException.class, () -> ProtocolJson.readTree(payload));
        assertThrows(
                IOException.class,
                () -> ProtocolJson.decode(payload, new TypeReference<Frame>() {}));
        assertNull(FrameCodec.decode(payload));
        assertNull(FrameCodec.decode(payload.getBytes(StandardCharsets.UTF_8)));
    }
}
