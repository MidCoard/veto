package top.focess.veto.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.focess.veto.agent.AgentRunner.ToolCallEvent;
import top.focess.veto.agent.AgentRunner.ToolResultEvent;
import top.focess.veto.agent.intercept.ApprovalDecision;
import top.focess.veto.agent.intercept.VetoOption;
import top.focess.veto.agent.intercept.VetoPrompt;
import top.focess.veto.api.llm.ToolCall;
import top.focess.veto.bus.DeltaBroker;
import top.focess.veto.bus.DeltaFrame;

/** Best-effort transport notifications; never owns execution or persistence. */
final class AgentEvents {
    private static final Logger log = LoggerFactory.getLogger("top.focess.veto.agent.AgentEvents");
    private final @NonNull String agentId;
    private final @NonNull ObjectMapper objectMapper;
    private final DeltaBroker deltaBroker;
    private final @NonNull Supplier<UUID> session;
    final @NonNull Listeners<String> messages = new Listeners<>("message");
    final @NonNull Listeners<String> thoughts = new Listeners<>("thought");
    final @NonNull Listeners<VetoPrompt> vetoes = new Listeners<>("veto");
    final @NonNull Listeners<ToolCallEvent> calls = new Listeners<>("tool-call");
    final @NonNull Listeners<ToolResultEvent> results = new Listeners<>("tool-result");

    AgentEvents(
            @NonNull String agentId,
            @NonNull ObjectMapper mapper,
            DeltaBroker broker,
            @NonNull Supplier<UUID> session) {
        this.agentId = agentId;
        this.objectMapper = mapper;
        this.deltaBroker = broker;
        this.session = session;
    }

    final class Listeners<T> {
        private final @NonNull List<Consumer<T>> listeners = new CopyOnWriteArrayList<>();
        private final @NonNull String kind;

        Listeners(@NonNull String kind) {
            this.kind = kind;
        }

        void add(@NonNull Consumer<T> listener) {
            listeners.add(listener);
        }

        void remove(@NonNull Consumer<T> listener) {
            listeners.remove(listener);
        }

        void emit(T event) {
            for (var listener : listeners) {
                try {
                    listener.accept(event);
                } catch (RuntimeException error) {
                    log.warn("Agent {} {} listener threw", agentId, kind, error);
                }
            }
        }
    }

    void message(@NonNull String text, int turn) {
        messages.emit(text);
        textFrame(DeltaFrame.Kind.ASSISTANT_MESSAGE, text, turn);
    }

    void thought(@NonNull String text, int turn) {
        if (text.isBlank()) return;
        thoughts.emit(text);
        textFrame(DeltaFrame.Kind.ASSISTANT_THOUGHT, text, turn);
    }

    private void textFrame(DeltaFrame.@NonNull Kind kind, @NonNull String text, int turn) {
        publishFrame(
                DeltaFrame.builder()
                        .sessionId(session.get())
                        .kind(kind)
                        .attr("turnNumber", turn)
                        .text(text)
                        .build());
    }

    void turn(@NonNull TurnRecord numbered) {
        // Transparency emission seam: forward tool calls + observations to subscribed transports
        // so the terminal can render a Claude-Code-style indicator. Emitted AFTER the DB persist
        // so listeners never see a turn the durable log lost. Only the two types whose wire
        // representation carries call/result fields are routed; other types (USER_PROMPT,
        // ASSISTANT_THOUGHT, ASSISTANT_RESPONSE) are already handled by the message/thought seams.
        switch (numbered.type()) {
            case TOOL_CALL -> {
                Object name = numbered.payload().get("tool_name");
                Object args = numbered.payload().get("args");
                Object callId = numbered.payload().get("call_id");
                if (name instanceof @NonNull String toolName) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> argMap =
                            args instanceof Map ? (Map<String, Object>) args : Map.of();
                    calls.emit(new ToolCallEvent(toolName, argMap));
                    // Domain event for every subscriber (web, terminal adapter): the call the agent
                    // is about to run. Carries the authoritative turnNumber + callId so a client
                    // can
                    // apply it incrementally and pair the later result without refetching history.
                    DeltaFrame.Builder b =
                            DeltaFrame.builder()
                                    .sessionId(session.get())
                                    .kind(DeltaFrame.Kind.TOOL_CALL)
                                    .attr("turnNumber", numbered.turnNumber())
                                    .attr("toolName", toolName)
                                    .attr("args", objectMapper.valueToTree(argMap))
                                    .text(toolName);
                    if (callId instanceof @NonNull String c) {
                        b.attr("callId", c);
                    }
                    publishFrame(b.build());
                }
            }
            case TOOL_RESPONSE -> {
                Object content = numbered.payload().get("content");
                Object success = numbered.payload().get("success");
                Object callId = numbered.payload().get("call_id");
                if (content instanceof @NonNull String body) {
                    results.emit(new ToolResultEvent(body, Boolean.TRUE.equals(success)));
                    DeltaFrame.Builder b =
                            DeltaFrame.builder()
                                    .sessionId(session.get())
                                    .kind(DeltaFrame.Kind.TOOL_RESULT)
                                    .attr("turnNumber", numbered.turnNumber())
                                    .attr("success", Boolean.TRUE.equals(success))
                                    .text(body);
                    if (callId instanceof @NonNull String c) {
                        b.attr("callId", c);
                    }
                    publishFrame(b.build());
                }
            }
            default -> {
                // no-op: message/thought seams already cover the other types
            }
        }
    }

    void publishFrame(@NonNull DeltaFrame frame) {
        if (deltaBroker == null) {
            return;
        }
        try {
            Map<String, JsonNode> attributes = new HashMap<>(frame.attrs());
            attributes.put("agentId", TextNode.valueOf(agentId));
            deltaBroker.publish(
                    new DeltaFrame(
                            frame.sessionId(),
                            frame.sequence(),
                            frame.emittedAt(),
                            frame.kind(),
                            frame.text(),
                            attributes));
        } catch (RuntimeException e) {
            log.warn("Agent {} delta-broker publish failed (kind={})", agentId, frame.kind(), e);
        }
    }

    void emitVetoRequired(
            @NonNull ToolCall call,
            ApprovalDecision.@NonNull Prompt p,
            @NonNull List<VetoOption> offered) {
        @NonNull String callId = call.callId();
        log.info(
                "VETO_REQUIRED agent={} callId={} tool={} scenario={} options={}",
                agentId,
                callId,
                call.toolName(),
                p.scenario(),
                offered);
        // Notify the veto emission seam: a transport renders a picker (a Prompt with a
        // VetoPayload) and routes the user's reply back to resolve the parked veto. The agent
        // parks in HitlRegistry regardless; a throwing listener is logged, not propagated.
        vetoes.emit(
                new VetoPrompt(
                        agentId,
                        callId,
                        call.toolName(),
                        p.scenario(),
                        offered,
                        call.args(),
                        p.danger()));
        // Domain event: a veto is parked and waiting for the user's decision. Subscribers (the web
        // UI, the terminal adapter) render a prompt from this instead of polling; the user's reply
        // still goes through the authenticated resolve path.
        DeltaFrame.Builder frame =
                DeltaFrame.builder()
                        .sessionId(session.get())
                        .kind(DeltaFrame.Kind.VETO_REQUIRED)
                        .attr("agentId", agentId)
                        .attr("callId", callId)
                        .attr("toolName", call.toolName())
                        .attr("scenario", p.scenario().name())
                        .attr("options", objectMapper.valueToTree(offered))
                        .attr("args", objectMapper.valueToTree(call.args()));
        // Danger rides the frame so the UI can warn prominently on DANGEROUS/CRITICAL calls.
        var danger = p.danger();
        if (danger != null) {
            frame.attr("danger", danger.name());
        }
        publishFrame(frame.text(call.toolName()).build());
    }
}
