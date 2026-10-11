package top.focess.veto.agent;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import top.focess.veto.api.agent.ToolCallEvent;
import top.focess.veto.api.llm.ToolCall;
import top.focess.veto.api.plugin.Scope;
import top.focess.veto.contract.Delta;
import top.focess.veto.contract.Frame;

class AgentEventsTest {
    @Test
    void completionPublishesExactlyOneScopedOutcome() {
        var frames = new ArrayList<Frame.ScopedFrame>();
        var session = UUID.randomUUID();
        var events =
                new AgentEvents(
                        "agent",
                        new ObjectMapper(),
                        frames::add,
                        session,
                        UUID.fromString("00000000-0000-0000-0000-000000000010"));
        events.completed("failed-request", 7, false, "failed");
        assertEquals(
                List.of(
                        new Frame.Error(
                                new Scope.AgentScope(
                                        UUID.fromString("00000000-0000-0000-0000-000000000010"),
                                        session.toString(),
                                        "agent"),
                                "failed-request",
                                7,
                                "failed",
                                0)),
                frames);
        frames.clear();
        events.completed("successful-request", 8, true, "ok");
        assertEquals(
                List.of(
                        new Frame.Done(
                                new Scope.AgentScope(
                                        UUID.fromString("00000000-0000-0000-0000-000000000010"),
                                        session.toString(),
                                        "agent"),
                                "successful-request",
                                Map.of("turnNumber", 8),
                                "ok")),
                frames);
    }

    @Test
    void toolOutputOwnsNestedJsonAndListenersCannotChangeHistoryOrFrame() throws Exception {
        var mapper = new ObjectMapper();
        var nested = new LinkedHashMap<String, Object>();
        var items = new ArrayList<@Nullable Object>();
        items.add("original");
        items.add(null);
        nested.put("items", items);
        var node = mapper.createObjectNode();
        node.put("amount", new BigDecimal("1.234567890123456789"));
        node.putNull("optional");
        node.putArray("values").add("node-original");
        var args = new LinkedHashMap<@NonNull String, Object>();
        args.put("nested", nested);
        args.put("node", node);
        var directEvent = new ToolCallEvent("tool", args);
        var call = new ToolCall("tool", args, "call");
        var history =
                new TurnRecord(
                        1, TurnType.TOOL_CALL, Map.of("tool_name", "tool", "args", args), null);
        String expected = mapper.writeValueAsString(args);
        nested.clear();
        items.clear();
        node.put("amount", 99);
        node.remove("optional");
        assertEquals(expected, mapper.writeValueAsString(directEvent.args()));
        assertEquals(expected, mapper.writeValueAsString(call.args()));
        assertEquals(expected, mapper.writeValueAsString(required(history.payload().get("args"))));

        var frames = new ArrayList<Delta>();
        var laterEvents = new ArrayList<ToolCallEvent>();
        var session = UUID.randomUUID();
        var events =
                new AgentEvents(
                        "agent",
                        mapper,
                        frame -> {
                            if (frame instanceof Delta delta) frames.add(delta);
                        },
                        session,
                        UUID.fromString("00000000-0000-0000-0000-000000000010"));
        events.calls.add(
                event -> {
                    if (event == null) throw new AssertionError("Missing tool event");
                    assertThrows(UnsupportedOperationException.class, event.args()::clear);
                    if (!(event.args().get("nested") instanceof Map<?, ?> object))
                        throw new AssertionError("Missing nested object");
                    assertThrows(UnsupportedOperationException.class, object::clear);
                    if (!(object.get("items") instanceof List<?> list))
                        throw new AssertionError("Missing nested array");
                    assertThrows(UnsupportedOperationException.class, list::clear);
                });
        events.calls.add(laterEvents::add);
        events.turn(history);
        assertEquals(1, laterEvents.size());
        assertEquals(expected, mapper.writeValueAsString(laterEvents.getFirst().args()));
        assertEquals(expected, mapper.writeValueAsString(required(history.payload().get("args"))));
        assertEquals(
                expected,
                mapper.writeValueAsString(((Delta.ToolCall) frames.getFirst().data()).args()));
    }

    private static @NonNull Object required(Object value) {
        if (value == null) throw new AssertionError("Missing published arguments");
        return value;
    }
}
