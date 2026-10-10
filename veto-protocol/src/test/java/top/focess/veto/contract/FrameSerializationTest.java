package top.focess.veto.contract;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;

class FrameSerializationTest {
    private static final @NonNull ObjectMapper JSON = new ObjectMapper();

    @Test
    void allVariantsUseOneDiscriminatorAndRoundTrip() throws Exception {
        var now = Instant.parse("2026-10-10T00:00:00Z");
        Frame[] frames = {
            new Frame.Hello(Frame.PROTOCOL_VERSION, 1, Version.parse("1.0.100"), "/workspace"),
            new Frame.Request("hello"),
            new Frame.Input("test input"),
            new Frame.Cancel(),
            new Frame.Bye(),
            new Frame.Heartbeat(9),
            new Frame.Welcome(Frame.PROTOCOL_VERSION, 1, Version.parse("1.0.100")),
            new Frame.Complete("/log", 2),
            new Frame.Hint("/login ", 3),
            new Frame.CompleteResult(List.of(new Frame.Completion("/login", null, null)), 2),
            new Frame.HintResult(new Frame.HintInfo("<user>", null), 3),
            new Frame.Done(Map.of("username", "alice"), null),
            new Frame.Error("failed", 0),
            new Frame.Progress("working", -1),
            new Frame.Prompt("Name", false),
            new Frame.Terminate("bye"),
            new Frame.HeartbeatAck(9, now),
            new Frame.Subscribe(null),
            new Frame.Unsubscribe(),
            new Frame.Process("payload", null, null, null),
            new Frame.Received("compile", 4, now),
            new Frame.VetoResult(4, "ALLOW", "payload", "ok", 0, true, now),
            new Frame.Subscribed("all", now),
            new Frame.Unsubscribed(now),
            new EventFrame(
                    UUID.randomUUID(),
                    4,
                    now,
                    EventFrame.Kind.TOOL_RESULT,
                    "observation",
                    Map.of()),
            EventFrame.command("command output")
        };
        for (var frame : frames) {
            var json = FrameCodec.encodeString(frame);
            assertTrue(JSON.readTree(json).path("type").isTextual());
            assertEquals(frame, FrameCodec.decode(json));
            assertEquals(frame, FrameCodec.decode(FrameCodec.encode(frame)));
        }
        var dag = DAGPayload.builder().id("task").taskType("compile").build();
        var forwarded = FrameCodec.decode(FrameCodec.encode(new Frame.DagPayload(dag, "sender")));
        assertInstanceOf(Frame.DagPayload.class, forwarded);
    }

    @Test
    void invalidRequiredFieldsAndOldFormatsAreRejected() {
        assertNull(FrameCodec.decode("{\"type\":\"request\",\"raw\":null}"));
        assertNull(FrameCodec.decode("{\"type\":\"veto.process\"}"));
        assertNull(FrameCodec.decode("{\"type\":\"heartbeat\",\"seq\":null}"));
        assertNull(FrameCodec.decode("{\"type\":\"delta\",\"content\":\"old\"}"));
        assertNull(FrameCodec.decode("{\"type\":\"tool_call\",\"toolName\":\"old\"}"));
        assertNull(FrameCodec.decode("{\"sessionId\":\"s\",\"kind\":\"NOTICE\"}"));
    }

    @Test
    void scalarTypesAreNotCoerced() {
        String[] payloads = {
            "{\"type\":\"request\",\"raw\":123}",
            "{\"type\":\"request\",\"raw\":true}",
            "{\"type\":\"heartbeat\",\"seq\":\"1\"}",
            "{\"type\":\"heartbeat\",\"seq\":1.5}",
            "{\"type\":\"prompt\",\"content\":\"Name\",\"mask\":1}",
            "{\"type\":\"prompt\",\"content\":\"Name\",\"mask\":\"true\"}"
        };
        for (var payload : payloads) {
            assertNull(FrameCodec.decode(payload));
            assertNull(FrameCodec.decode(payload.getBytes(StandardCharsets.UTF_8)));
        }
    }

    @Test
    void serializationFailureUsesStandardIoException() {
        var frame = new Frame.Done(Map.of("unsupported", new Object()), null);
        assertThrows(UncheckedIOException.class, () -> FrameCodec.encode(frame));
        assertThrows(UncheckedIOException.class, () -> FrameCodec.encodeString(frame));
    }
}
