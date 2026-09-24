package top.focess.veto.agent;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.AgentRuntimeState.WaitReason;
import top.focess.veto.agent.intercept.ApprovalReceipt;
import top.focess.veto.agent.intercept.VetoPrompt;
import top.focess.veto.agent.loop.LoopBreaker;
import top.focess.veto.agent.loop.MessageCitations;
import top.focess.veto.agent.loop.PromptCompiler;
import top.focess.veto.agent.loop.PromptSource;
import top.focess.veto.agent.tool.ToolDefinition;
import top.focess.veto.api.agent.ToolCallEvent;
import top.focess.veto.api.agent.ToolResultEvent;
import top.focess.veto.api.agent.tool.ToolErrorCode;
import top.focess.veto.api.agent.tool.ToolResult;
import top.focess.veto.api.llm.ToolCall;
import top.focess.veto.api.llm.VetoResponse;
import top.focess.veto.bus.DeltaFrame;

/** Appends durable history and emits corresponding events in order. */
final class AgentOutput {
    private final @NonNull AgentRuntimeState runtime;

    AgentOutput(@NonNull AgentRuntimeState runtime) {
        this.runtime = runtime;
    }

    void appendThought(@NonNull VetoResponse response) {
        String thought = response.thought();
        // Provider-exposed reasoning is display text; native replay state lives on tool calls.
        Map<String, Object> payload = new HashMap<>();
        if (thought != null && !thought.isBlank()) {
            payload.put("response", thought);
            payload.put("provider_reasoning", true);
            payload.put("response_format", "text");
        } else {
            return;
        }
        if (runtime.lastModelCallId != null) payload.put("model_call_id", runtime.lastModelCallId);
        runtime.output()
                .appendTurn(
                        new TurnRecord(
                                ++runtime.turnNumber, TurnType.ASSISTANT_THOUGHT, payload, null));
        // Stream the thought to transports now (after it is durably recorded). The terminal
        // renders it dimmed/muted ahead of the user-facing message that follows, so the user
        // can follow the reasoning without it competing with the answer.
        emitThought(thought);
    }

    void emitMessage(@NonNull String message) {
        emitMessage(message, null);
    }

    void emitMessage(@NonNull String message, MessageCitations.Bound citations) {
        emitMessage(message, citations, null);
    }

    void emitMessage(
            @NonNull String message, MessageCitations.Bound citations, String modelCallId) {
        emitMessage(message, citations, modelCallId, false);
    }

    void emitMessage(
            @NonNull String message,
            MessageCitations.Bound citations,
            String modelCallId,
            boolean runtimeForwarded) {
        emitMessage(message, citations, modelCallId, runtimeForwarded, false);
    }

    void emitMessage(
            @NonNull String message,
            MessageCitations.Bound citations,
            String modelCallId,
            boolean runtimeForwarded,
            boolean nativeResponseText) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (runtimeForwarded) payload.put("runtimeOutputTokens", 0L);
        if (nativeResponseText) payload.put("native_response_text", true);
        payload.put("content", message);
        if (modelCallId != null) payload.put("model_call_id", modelCallId);
        if (citations != null && !citations.checks().isEmpty())
            payload.put("citation_context", citations);
        runtime.output()
                .appendTurn(
                        new TurnRecord(
                                ++runtime.turnNumber, TurnType.ASSISTANT_RESPONSE, payload, null));
        runtime.lastMessage = message;
        runtime.events.message(message, runtime.turnNumber);
    }

    void emitThought(@NonNull String thought) {
        runtime.events.thought(thought, runtime.turnNumber);
    }

    void publishFrame(@NonNull DeltaFrame frame) {
        runtime.events.publishFrame(frame);
    }

    void tripBreaker() {
        runtime.lifecycle().saveExecutionWait(WaitReason.BREAKER);
        runtime.awaitingBreakerContinuation = true;
        String notice = LoopBreaker.tripNotice(runtime.locale);
        emitMessage(notice);
        runtime.output()
                .publishFrame(
                        DeltaFrame.builder()
                                .sessionId(runtime.sessionId)
                                .kind(DeltaFrame.Kind.BREAKER_TRIPPED)
                                .attr("turnNumber", runtime.turnNumber)
                                .attr("maxCallsPerEpisode", runtime.breaker.maxCallsPerEpisode())
                                .text(notice)
                                .build());
    }

    void appendObservation(@NonNull String toolName, @NonNull String content) {
        appendToolResponse(toolName, null, content, false);
    }

    void appendToolResponse(
            @NonNull String toolName, String callId, @NonNull String content, boolean success) {
        runtime.output()
                .appendToolResponse(
                        success
                                ? ToolResult.success(toolName, callId, content)
                                : ToolResult.failure(
                                        toolName,
                                        callId,
                                        content,
                                        ToolErrorCode.GENERIC.TOOL_FAILURE));
    }

    void appendToolResponse(@NonNull ToolResult result) {
        String presented =
                runtime.toolResultPresenter.present(result, runtime.toolResultPresentation);
        TurnRecord turn =
                TurnRecord.presentedToolResponse(
                        ++runtime.turnNumber, result, presented, runtime.toolResultPresentation);
        String responseCallId = result.callId();
        ApprovalReceipt receipt =
                responseCallId == null ? null : runtime.approvalReceipts.remove(responseCallId);
        if (receipt != null) {
            Map<String, Object> payload = new HashMap<>(turn.payload());
            payload.put("approval", receipt);
            turn = new TurnRecord(turn.turnNumber(), turn.type(), payload, turn.timestamp());
        }
        appendTurn(turn);
    }

    void recordUsage(int throughTurn, @NonNull UsageMeasurement measurement) {
        runtime.journal.recordUsage(throughTurn, measurement);
    }

    void appendToolCall(@NonNull ToolCall call) {
        TurnRecord turn = TurnRecord.toolCall(++runtime.turnNumber, call);
        String origin = runtime.currentToolModelCallId;
        Map<String, Object> payload = new LinkedHashMap<>(turn.payload());
        if (origin != null) payload.put("model_call_id", origin);
        ToolDefinition definition = runtime.toolEngine.resolveDefinition(call.toolName());
        if (definition != null) {
            payload.put("tool_origin", definition.origin());
            var provenance = definition.provenance();
            if (provenance != null) {
                payload.put("plugin_id", provenance.pluginId());
            }
        }
        turn = new TurnRecord(turn.turnNumber(), turn.type(), payload, turn.timestamp());
        appendTurn(turn);
    }

    void appendTurn(@NonNull TurnRecord turn) {
        turn = runtime.promptCompiler.recordRuntimeSource(turn);
        WaitReason waiting = runtime.executionWait;
        boolean required =
                turn.type() == TurnType.MONITOR_EVENT
                        || (turn.type() == TurnType.TOOL_RESPONSE
                                && waiting == WaitReason.QUESTION);
        if (turn.type() == TurnType.AGENT_INIT) {
            Map<String, Object> metadata = new LinkedHashMap<>(turn.payload());
            metadata.put("contextMaxTokens", runtime.binding.options().contextWindowOrDefault());
            PromptSource.Rendered source = runtime.currentSystemSource;
            if (source != null
                    && !source.sources().isEmpty()
                    && source.text().equals(metadata.get("system_prompt"))) {
                metadata.put(
                        "prompt_source",
                        Map.of(
                                "id",
                                source.id(),
                                "version",
                                2,
                                "message",
                                "system",
                                "spans",
                                source.sources()));
            }
            turn =
                    new TurnRecord(
                            turn.turnNumber(),
                            turn.type(),
                            metadata,
                            turn.timestamp(),
                            turn.llmUsage());
        }
        turn = RecordTokenCounter.unmeasured(turn);
        TurnRecord numbered = runtime.journal.append(turn, required);
        runtime.turnNumber = numbered.turnNumber();
        runtime.events.turn(numbered);
    }

    void setRecoveredTasks(@NonNull List<RecoveredTask> tasks) {
        synchronized (runtime) {
            runtime.recoveryContext =
                    PromptCompiler.compileMessage("runtime-recovery", Map.of("tasks", tasks));
        }
    }

    void seedHistory(@NonNull List<TurnRecord> replayed) {
        synchronized (runtime) {
            if (!runtime.journal.snapshot().isEmpty() || replayed.isEmpty()) return;
            runtime.turnNumber = runtime.journal.seed(replayed);
            runtime.recoveredWait = RecordRecovery.requiresExplicitContinuation(replayed);
        }
    }

    void addMessageListener(@NonNull Consumer<String> listener) {
        runtime.events.messages.add(listener);
    }

    void removeMessageListener(@NonNull Consumer<String> listener) {
        runtime.events.messages.remove(listener);
    }

    void addThoughtListener(@NonNull Consumer<String> listener) {
        runtime.events.thoughts.add(listener);
    }

    void removeThoughtListener(@NonNull Consumer<String> listener) {
        runtime.events.thoughts.remove(listener);
    }

    void addVetoListener(@NonNull Consumer<VetoPrompt> listener) {
        runtime.events.vetoes.add(listener);
    }

    void removeVetoListener(@NonNull Consumer<VetoPrompt> listener) {
        runtime.events.vetoes.remove(listener);
    }

    void addToolCallListener(@NonNull Consumer<ToolCallEvent> listener) {
        runtime.events.calls.add(listener);
    }

    void removeToolCallListener(@NonNull Consumer<ToolCallEvent> listener) {
        runtime.events.calls.remove(listener);
    }

    void addToolResultListener(@NonNull Consumer<ToolResultEvent> listener) {
        runtime.events.results.add(listener);
    }

    void removeToolResultListener(@NonNull Consumer<ToolResultEvent> listener) {
        runtime.events.results.remove(listener);
    }

    @NonNull List<TurnRecord> history() {
        return runtime.journal.snapshot();
    }
}
