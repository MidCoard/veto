package top.focess.veto.contract;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FrameCodecTest {

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
            var bytes = FrameCodec.encode(original);
            var text = FrameCodec.encodeString(original);
            assertArrayEquals(text.getBytes(StandardCharsets.UTF_8), bytes);
            assertEquals(original, FrameCodec.decode(bytes));
            assertEquals(original, FrameCodec.decode(text));
            var envelope = new ObjectMapper().readTree(text);
            assertEquals(original.emittedAt().toString(), envelope.path("emittedAt").asText());
            assertEquals(kind.name(), envelope.path("kind").asText());
            assertEquals(attrs, envelope.path("attrs").path("detail"));
        }
    }

    @Test
    void textAndByteTransportsUseTheSameWireContract() {
        var heartbeat = new Frame.Heartbeat(9);
        assertEquals("{\"type\":\"heartbeat\",\"seq\":9}", FrameCodec.encodeString(heartbeat));
        Frame original = new Frame.Done(Map.of("username", "alice"), "完成");
        assertArrayEquals(FrameCodec.encodeString(original).getBytes(StandardCharsets.UTF_8), FrameCodec.encode(original));
        assertEquals(original, FrameCodec.decode(FrameCodec.encode(original)));
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
        var frame = new Frame.DagPayload(original, "terminal");
        if (!(FrameCodec.decode(FrameCodec.encode(frame)) instanceof Frame.DagPayload decoded))
            throw new AssertionError("Missing DAG frame");
        var parsed = decoded.data();
        assertEquals(original.id(), parsed.id());
        assertEquals(original.taskType(), parsed.taskType());
        assertEquals(original.parameters(), parsed.parameters());
        assertEquals(original.dependencies(), parsed.dependencies());
        assertEquals(original.status(), parsed.status());
        assertEquals(original.createdAt(), parsed.createdAt());
        assertEquals(original.updatedAt(), parsed.updatedAt());
        assertEquals(original.sourceComponent(), parsed.sourceComponent());
        assertEquals(original.targetComponent(), parsed.targetComponent());
        assertThrows(
                UnsupportedOperationException.class, () -> parsed.parameters().put("new", "value"));
        assertThrows(UnsupportedOperationException.class, () -> parsed.dependencies().add("new"));
        assertNull(FrameCodec.decode("{\"type\":\"dag.payload\",\"data\":{}}"));
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
