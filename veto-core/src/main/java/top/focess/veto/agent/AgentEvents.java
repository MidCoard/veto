package top.focess.veto.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.focess.veto.agent.intercept.ApprovalDecision;
import top.focess.veto.agent.intercept.VetoOption;
import top.focess.veto.agent.intercept.VetoPrompt;
import top.focess.veto.api.agent.ToolCallEvent;
import top.focess.veto.api.agent.ToolResultEvent;
import top.focess.veto.api.llm.ToolCall;
import top.focess.veto.api.plugin.Scope;
import top.focess.veto.contract.Delta;
import top.focess.veto.contract.Frame;

/**
 * Inline agent-output listeners and transport notifications; never owns execution or persistence.
 *
 * <p>Each emission visits a copy-on-write listener snapshot in registration order on the calling
 * thread, then publishes its frame. Runtime exceptions are logged and isolated; errors propagate.
 * Subscription changes are safe during emission and affect subsequent snapshots. Distinct emissions
 * may overlap, so callbacks and the supplied sink must tolerate concurrent callers. There is no
 * notification executor, global emission order, or deep payload snapshot here.
 */
final class AgentEvents {
    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.agent.AgentEvents");
    private final Scope.@NonNull AgentScope scope;
    private final @NonNull ObjectMapper objectMapper;
    private final @NonNull AgentEventSink eventSink;
    final @NonNull Listeners<String> messages = new Listeners<>("message");
    final @NonNull Listeners<String> thoughts = new Listeners<>("thought");
    final @NonNull Listeners<VetoPrompt> vetoes = new Listeners<>("veto");
    final @NonNull Listeners<ToolCallEvent> calls = new Listeners<>("tool-call");
    final @NonNull Listeners<ToolResultEvent> results = new Listeners<>("tool-result");

    AgentEvents(
            @NonNull String agentId,
            @NonNull ObjectMapper mapper,
            @NonNull AgentEventSink eventSink,
            @NonNull UUID sessionId,
            @NonNull UUID userId) {
        this.scope = new Scope.AgentScope(userId, sessionId.toString(), agentId);
        this.objectMapper = mapper;
        this.eventSink = eventSink;
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
                    log.warn("Agent {} {} listener threw", scope.agent(), kind, error);
                }
            }
        }
    }

    void executionChanged() {
        publishFrame(new Frame.Action(scope.sessionScope(), List.of("agents", "execution")));
    }

    void message(@NonNull String text, int turn) {
        messages.emit(text);
        publish(new Delta.Message(text, turn));
    }

    void thought(@NonNull String text, int turn) {
        if (text.isBlank()) return;
        thoughts.emit(text);
        publish(new Delta.Thought(text, turn));
    }

    void publish(Delta.@NonNull Data data) {
        publishFrame(new Delta(scope, data));
    }

    void usage(int turnNumber, @NonNull UsageMeasurement measurement) {
        var callId = measurement.modelCallId();
        if (callId == null) throw new IllegalArgumentException("Measured calls need an identity");
        publish(
                new Delta.TokenUsage(
                        callId,
                        measurement.inputTokens(),
                        measurement.outputTokens(),
                        measurement.cacheReadInputTokens(),
                        measurement.cacheCreationInputTokens(),
                        measurement.contextMaxTokens(),
                        measurement.model(),
                        measurement.provider(),
                        measurement.purpose(),
                        turnNumber));
    }

    void completed(String requestId, int turnNumber, boolean success, @NonNull String message) {
        if (success) {
            publishFrame(new Frame.Done(scope, requestId, turnNumber, message, null));
        } else {
            publishFrame(new Frame.Error(scope, requestId, turnNumber, message, 0, null));
        }
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
                    var argMap =
                            args instanceof Map<?, ?> values
                                    ? ToolCall.snapshotArguments(values)
                                    : Map.<String, Object>of();
                    ObjectNode argumentFrame = objectMapper.valueToTree(argMap);
                    calls.emit(new ToolCallEvent(toolName, argMap));
                    publish(
                            new Delta.ToolCall(
                                    callId instanceof String c ? c : null,
                                    toolName,
                                    argumentFrame,
                                    numbered.turnNumber()));
                }
            }
            case TOOL_RESPONSE -> {
                Object content = numbered.payload().get("content");
                Object success = numbered.payload().get("success");
                Object callId = numbered.payload().get("call_id");
                if (content instanceof @NonNull String body) {
                    results.emit(new ToolResultEvent(body, Boolean.TRUE.equals(success)));
                    publish(
                            new Delta.ToolResult(
                                    callId instanceof String c ? c : null,
                                    body,
                                    Boolean.TRUE.equals(success),
                                    numbered.turnNumber()));
                }
            }
            default -> {
                // no-op: message/thought seams already cover the other types
            }
        }
    }

    void publishFrame(Frame.@NonNull ScopedFrame frame) {
        try {
            eventSink.publish(frame);
        } catch (RuntimeException e) {
            log.warn(
                    "Agent {} delta-broker publish failed (kind={})",
                    scope.agent(),
                    frame.getClass().getSimpleName(),
                    e);
        }
    }

    void emitVetoRequired(
            @NonNull ToolCall call,
            ApprovalDecision.@NonNull Prompt p,
            @NonNull List<VetoOption> offered,
            long seq) {
        String callId = call.callId();
        log.info(
                "VETO_REQUIRED agent={} callId={} tool={} scenario={} options={}",
                scope.agent(),
                callId,
                call.toolName(),
                p.scenario(),
                offered);
        // Notify the veto emission seam: a transport renders a picker from a Veto frame and routes
        // the user's reply back to resolve the parked veto. The agent
        // parks in HitlRegistry regardless; a throwing listener is logged, not propagated.
        vetoes.emit(
                new VetoPrompt(
                        scope.agent(),
                        callId,
                        call.toolName(),
                        p.scenario(),
                        offered,
                        call.args(),
                        p.danger(),
                        p.relevance(),
                        seq));
        var danger = p.danger();
        var relevance = p.relevance();
        publishFrame(
                new Frame.Veto(
                        scope,
                        callId,
                        call.toolName(),
                        p.scenario().name(),
                        offered.stream().map(Enum::name).toList(),
                        call.args(),
                        danger,
                        relevance,
                        seq));
    }
}
