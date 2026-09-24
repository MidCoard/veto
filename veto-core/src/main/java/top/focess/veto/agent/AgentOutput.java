package top.focess.veto.agent;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.intercept.ApprovalDecision;
import top.focess.veto.agent.intercept.ApprovalReceipt;
import top.focess.veto.agent.intercept.VetoOption;
import top.focess.veto.agent.intercept.VetoPrompt;
import top.focess.veto.agent.loop.MessageCitations;
import top.focess.veto.agent.loop.PromptCompiler;
import top.focess.veto.agent.loop.PromptSource;
import top.focess.veto.agent.tool.ToolDefinition;
import top.focess.veto.agent.tool.ToolEngine;
import top.focess.veto.api.agent.ToolCallEvent;
import top.focess.veto.api.agent.ToolResultEvent;
import top.focess.veto.api.agent.tool.ToolErrorCode;
import top.focess.veto.api.agent.tool.ToolResult;
import top.focess.veto.api.llm.ToolCall;
import top.focess.veto.api.llm.ToolResultPresentationMode;
import top.focess.veto.api.llm.VetoResponse;
import top.focess.veto.bus.DeltaFrame;
import top.focess.veto.llm.core.ToolResultPresenter;
import top.focess.veto.util.Nullness;

/** Appends durable history and emits corresponding events in order. */
final class AgentOutput {
    record View(
            RequestHandle request,
            @NonNull ToolResultPresentationMode presentation,
            boolean question,
            int contextMaxTokens) {}

    private final @NonNull AgentHistory journal;
    private final @NonNull AgentEvents events;
    private final @NonNull PromptCompiler compiler;
    private final @NonNull ToolResultPresenter presenter;
    private final @NonNull ToolEngine tools;
    private final @NonNull Supplier<View> view;
    private PromptSource.Rendered systemSource;

    AgentOutput(
            @NonNull AgentHistory journal,
            @NonNull AgentEvents events,
            @NonNull PromptCompiler compiler,
            @NonNull ToolResultPresenter presenter,
            @NonNull ToolEngine tools,
            @NonNull Supplier<View> view) {
        this.journal = journal;
        this.events = events;
        this.compiler = compiler;
        this.presenter = presenter;
        this.tools = tools;
        this.view = view;
    }

    int turnNumber() {
        return journal.turnNumber();
    }

    int nextTurn() {
        return turnNumber() + 1;
    }

    void systemSource(PromptSource.@NonNull Rendered source) {
        systemSource = source;
    }

    @NonNull Object requestIdentity() {
        var request = view.get().request();
        return request == null ? this : request;
    }

    void appendThought(@NonNull VetoResponse response, String modelCallId) {
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
        if (modelCallId != null) payload.put("model_call_id", modelCallId);
        appendTurn(new TurnRecord(nextTurn(), TurnType.ASSISTANT_THOUGHT, payload, null));
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
        appendTurn(new TurnRecord(nextTurn(), TurnType.ASSISTANT_RESPONSE, payload, null));
        RequestHandle request = view.get().request();
        if (request != null) request.message = message;
        events.message(message, turnNumber());
    }

    void emitThought(@NonNull String thought) {
        events.thought(thought, turnNumber());
    }

    void publishFrame(@NonNull DeltaFrame frame) {
        events.publishFrame(frame);
    }

    void appendObservation(@NonNull String toolName, @NonNull String content) {
        appendToolResponse(toolName, null, content, false);
    }

    void appendToolResponse(
            @NonNull String toolName, String callId, @NonNull String content, boolean success) {
        appendToolResponse(
                success
                        ? ToolResult.success(toolName, callId, content)
                        : ToolResult.failure(
                                toolName, callId, content, ToolErrorCode.GENERIC.TOOL_FAILURE));
    }

    void appendToolResponse(@NonNull ToolResult result) {
        String presented = presenter.present(result, view.get().presentation());
        TurnRecord turn =
                TurnRecord.presentedToolResponse(
                        nextTurn(), result, presented, view.get().presentation());
        String responseCallId = result.callId();
        ApprovalReceipt receipt =
                responseCallId == null
                        ? null
                        : Nullness.requireNonNull(view.get().request())
                                .approvalReceipts
                                .remove(responseCallId);
        if (receipt != null) {
            Map<String, Object> payload = new HashMap<>(turn.payload());
            payload.put("approval", receipt);
            turn = new TurnRecord(turn.turnNumber(), turn.type(), payload, turn.timestamp());
        }
        appendTurn(turn);
    }

    void recordUsage(int throughTurn, @NonNull UsageMeasurement measurement) {
        journal.recordUsage(throughTurn, measurement);
    }

    void appendToolCall(@NonNull ToolCall call, String origin) {
        TurnRecord turn = TurnRecord.toolCall(nextTurn(), call);
        Map<String, Object> payload = new LinkedHashMap<>(turn.payload());
        if (origin != null) payload.put("model_call_id", origin);
        ToolDefinition definition = tools.resolveDefinition(call.toolName());
        if (definition != null) {
            payload.put("tool_origin", definition.origin());
            var provenance = definition.provenance();
            if (provenance != null) {
                payload.put("plugin_id", provenance.pluginId());
                var localId = provenance.localId();
                if (localId != null) payload.put("tool_local_id", localId);
            }
        }
        turn = new TurnRecord(turn.turnNumber(), turn.type(), payload, turn.timestamp());
        appendTurn(turn);
    }

    void appendTurn(@NonNull TurnRecord turn) {
        turn = compiler.recordRuntimeSource(turn);
        boolean question = view.get().question();
        boolean required =
                (turn.type() == TurnType.RUNTIME_EVENT || turn.type() == TurnType.MONITOR_EVENT)
                        || (turn.type() == TurnType.TOOL_RESPONSE && question);
        if (turn.type() == TurnType.AGENT_INIT) {
            Map<String, Object> metadata = new LinkedHashMap<>(turn.payload());
            metadata.put("contextMaxTokens", view.get().contextMaxTokens());
            PromptSource.Rendered source = systemSource;
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
        TurnRecord numbered = journal.append(turn, required);

        events.turn(numbered);
    }

    boolean seedHistory(@NonNull List<TurnRecord> replayed) {
        if (!journal.snapshot().isEmpty() || replayed.isEmpty()) return false;
        journal.seed(replayed);
        return RecordRecovery.requiresExplicitContinuation(replayed);
    }

    void emitVetoRequired(
            @NonNull ToolCall call,
            ApprovalDecision.@NonNull Prompt prompt,
            @NonNull List<VetoOption> offered) {
        events.emitVetoRequired(call, prompt, offered);
    }

    void addMessageListener(@NonNull Consumer<String> listener) {
        events.messages.add(listener);
    }

    void removeMessageListener(@NonNull Consumer<String> listener) {
        events.messages.remove(listener);
    }

    void addThoughtListener(@NonNull Consumer<String> listener) {
        events.thoughts.add(listener);
    }

    void removeThoughtListener(@NonNull Consumer<String> listener) {
        events.thoughts.remove(listener);
    }

    void addVetoListener(@NonNull Consumer<VetoPrompt> listener) {
        events.vetoes.add(listener);
    }

    void removeVetoListener(@NonNull Consumer<VetoPrompt> listener) {
        events.vetoes.remove(listener);
    }

    void addToolCallListener(@NonNull Consumer<ToolCallEvent> listener) {
        events.calls.add(listener);
    }

    void removeToolCallListener(@NonNull Consumer<ToolCallEvent> listener) {
        events.calls.remove(listener);
    }

    void addToolResultListener(@NonNull Consumer<ToolResultEvent> listener) {
        events.results.add(listener);
    }

    void removeToolResultListener(@NonNull Consumer<ToolResultEvent> listener) {
        events.results.remove(listener);
    }

    @NonNull List<TurnRecord> history() {
        return journal.snapshot();
    }
}
