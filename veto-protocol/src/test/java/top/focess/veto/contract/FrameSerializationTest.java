package top.focess.veto.contract;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.veto.api.plugin.Scope;

class FrameSerializationTest {
    private static final @NonNull ObjectMapper JSON = new ObjectMapper();

    @Test
    void everyGeneralOutputRequiresExplicitScope() throws Exception {
        Frame[] frames = {
            new Frame.Done("ok"), Frame.Error.ofError("failed"), Delta.message("text")
        };
        for (var frame : frames) {
            var json = (ObjectNode) JSON.readTree(FrameCodec.encodeString(frame));
            assertEquals("global", json.path("scope").path("type").asText());
            assertEquals(frame, FrameCodec.decode(json.toString()));
            json.putNull("scope");
            assertNull(FrameCodec.decode(json.toString()));
            json.remove("scope");
            assertNull(FrameCodec.decode(json.toString()));
        }
    }

    @Test
    void allVariantsUseOneDiscriminatorAndRoundTrip() throws Exception {
        var now = Instant.parse("2026-10-10T00:00:00Z");
        Frame[] frames = {
            new Frame.Hello(Frame.PROTOCOL_VERSION, 1, Version.parse("1.0.100"), "/workspace"),
            new Frame.Request("hello"),
            new Frame.Input("test input"),
            new Frame.Cancel(),
            new Frame.Bye(),
            new Frame.Heartbeat(),
            new Frame.Welcome(Frame.PROTOCOL_VERSION, 1, Version.parse("1.0.100")),
            new Frame.Complete("/log", 2),
            new Frame.Hint("/login ", 3),
            new Frame.CompleteResult(List.of(new Frame.Completion("/login", null, null)), 2),
            new Frame.HintResult(new Frame.HintInfo("<user>", null), 3),
            new Frame.Done(null),
            new Frame.Error(
                    new Scope.AgentScope(
                            UUID.fromString("00000000-0000-0000-0000-000000000010"),
                            (UUID.randomUUID()).toString(),
                            "agent"),
                    "request",
                    7,
                    "failed",
                    0,
                    null),
            new Frame.Error("failed", 0),
            new Delta(new Scope.GlobalScope(), new Delta.Progress("working", -1)),
            new Frame.Prompt("Name", false),
            new Frame.Veto(
                    new Scope.AgentScope(
                            UUID.fromString("00000000-0000-0000-0000-000000000010"),
                            (UUID.randomUUID()).toString(),
                            "agent"),
                    "call",
                    "tool",
                    "EXEC",
                    List.of("DECLINE"),
                    Map.of(),
                    null,
                    null,
                    42),
            new Frame.VetoResult(
                    new Scope.AgentScope(
                            UUID.fromString("00000000-0000-0000-0000-000000000010"),
                            (UUID.randomUUID()).toString(),
                            "agent"),
                    "call",
                    "DECLINE",
                    2),
            new Frame.Terminate("bye"),
            new Frame.HeartbeatAck(now),
            new Delta(
                    new Scope.AgentScope(
                            UUID.fromString("00000000-0000-0000-0000-000000000010"),
                            (UUID.randomUUID()).toString(),
                            "agent"),
                    4,
                    now,
                    new Delta.ToolResult("call", "observation", true, 1)),
            Delta.message("command output")
        };
        for (var frame : frames) {
            var json = FrameCodec.encodeString(frame);
            assertTrue(JSON.readTree(json).path("type").isTextual());
            assertEquals(frame, FrameCodec.decode(json));
            assertEquals(frame, FrameCodec.decode(FrameCodec.encode(frame)));
        }
    }

    @Test
    void commandInputAndCompletionHaveExplicitContracts() throws Exception {
        var prompt = JSON.readTree(FrameCodec.encodeString(new Frame.Prompt("Password", true)));
        assertFalse(prompt.has("scope"));
        var done =
                JSON.readTree(
                        FrameCodec.encodeString(
                                new Frame.Done(
                                        new Scope.GlobalScope(), null, 3, true, null, null)));
        assertFalse(done.has("meta"));
        assertEquals(3, done.path("turnNumber").asInt());
        assertTrue(done.path("cancelled").asBoolean());
        assertTrue(
                List.of(Frame.ServerFrame.class.getPermittedSubclasses())
                        .contains(Frame.Veto.class));
    }

    @Test
    void agentDataRequiresAnAgentScopeInMemoryAndOnTheWire() throws Exception {
        var owner = UUID.randomUUID();
        var agent = new Scope.AgentScope(owner, "session", "agent");
        Delta.AgentData[] data = {
            new Delta.TokenUsage("call", 100, 20, null, null, 8192, null, null, null, 1),
            new Delta.Notice("hint", Delta.Level.INFO),
            new Delta.Thought("thinking", 1),
            new Delta.ToolCall("call", "tool", JSON.createObjectNode(), 1),
            new Delta.ToolResult("call", "result", true, 1)
        };
        for (var update : data) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new Delta(new Scope.GlobalScope(), update));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new Delta(new Scope.SessionScope(owner, "session"), update));
            var frame = new Delta(agent, update);
            assertEquals(frame, FrameCodec.decode(FrameCodec.encode(frame)));
            var tree = (ObjectNode) JSON.readTree(FrameCodec.encodeString(frame));
            tree.putNull("scope");
            assertNull(FrameCodec.decode(tree.toString()));
        }
        assertEquals(-1, new Frame.Done(null).turnNumber());
        assertEquals(-1, Frame.Error.ofError("command").turnNumber());
    }

    @Test
    void invalidRequiredFieldsAndOldFormatsAreRejected() {
        assertNull(FrameCodec.decode("{\"type\":\"request\",\"raw\":null}"));
        assertNull(FrameCodec.decode("{\"type\":\"process\"}"));
        assertNull(FrameCodec.decode("{\"type\":\"error\",\"content\":\"bad\",\"seq\":null}"));
        assertNull(FrameCodec.decode("{\"type\":\"delta\",\"content\":\"old\"}"));
        assertNull(FrameCodec.decode("{\"type\":\"tool_call\",\"toolName\":\"old\"}"));
        assertNull(FrameCodec.decode("{\"sessionId\":\"s\",\"kind\":\"NOTICE\"}"));
    }

    @Test
    void scalarTypesAreNotCoerced() {
        String[] payloads = {
            "{\"type\":\"request\",\"raw\":123}",
            "{\"type\":\"request\",\"raw\":true}",
            "{\"type\":\"error\",\"content\":\"bad\",\"seq\":\"1\"}",
            "{\"type\":\"error\",\"content\":\"bad\",\"seq\":1.5}",
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
        var frame =
                new Frame.Veto(
                        new Scope.AgentScope(
                                UUID.randomUUID(), UUID.randomUUID().toString(), "agent"),
                        "call",
                        "tool",
                        "GENERIC",
                        List.of("DECLINE"),
                        Map.of("unsupported", new Object()),
                        null,
                        null,
                        1);
        assertThrows(UncheckedIOException.class, () -> FrameCodec.encode(frame));
        assertThrows(UncheckedIOException.class, () -> FrameCodec.encodeString(frame));
    }
}
