package top.focess.veto.agent;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import top.focess.veto.agent.AgentRunner.VetoRefusedException;
import top.focess.veto.agent.intercept.ApprovalDecision;
import top.focess.veto.agent.intercept.ApprovalReceipt;
import top.focess.veto.agent.intercept.VetoOption;
import top.focess.veto.agent.loop.LoopBreaker;
import top.focess.veto.agent.loop.MessageCitations;
import top.focess.veto.agent.loop.PromptCompiler;
import top.focess.veto.agent.loop.PromptSource;
import top.focess.veto.agent.tool.ToolDefinition;
import top.focess.veto.agent.tool.ToolEngine;
import top.focess.veto.api.agent.AgentResult;
import top.focess.veto.api.agent.tool.ToolErrorCode;
import top.focess.veto.api.agent.tool.ToolResult;
import top.focess.veto.api.llm.ToolCall;
import top.focess.veto.api.llm.ToolResultPresentationMode;
import top.focess.veto.api.llm.VetoResponse;
import top.focess.veto.api.llm.exceptions.CredentialException;
import top.focess.veto.api.llm.exceptions.LlmAuthException;
import top.focess.veto.api.llm.exceptions.LlmRateLimitException;
import top.focess.veto.api.llm.exceptions.LlmTimeoutException;
import top.focess.veto.api.llm.exceptions.ModelCapabilityException;
import top.focess.veto.api.llm.exceptions.ModelSchemaException;
import top.focess.veto.contract.EventFrame;
import top.focess.veto.i18n.Msg;
import top.focess.veto.llm.core.ToolResultPresenter;
import top.focess.veto.util.Nullness;
import top.focess.veto.vault.KeysteadVault;

/** Appends durable history and emits corresponding events in order. */
final class AgentOutput {
    record View(
            RequestHandle request,
            @NonNull ToolResultPresentationMode presentation,
            boolean question,
            int contextMaxTokens,
            @NonNull Locale locale) {}

    private final @NonNull AgentHistory journal;
    final @NonNull AgentEvents events;
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
        Map<@NonNull String, @Nullable Object> payload = new HashMap<>();
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
        Map<@NonNull String, @Nullable Object> payload = new LinkedHashMap<>();
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

    void publishFrame(@NonNull EventFrame frame) {
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
            Map<@NonNull String, @Nullable Object> payload = new HashMap<>(turn.payload());
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
        Map<@NonNull String, @Nullable Object> payload = new LinkedHashMap<>(turn.payload());
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
            Map<@NonNull String, @Nullable Object> metadata = new LinkedHashMap<>(turn.payload());
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

    String latestUserTask() {
        var turns = history();
        for (int i = turns.size() - 1; i >= 0; i--) {
            var turn = turns.get(i);
            if (turn.type() != TurnType.USER_PROMPT) continue;
            for (var key : List.of("resume_context", "content")) {
                if (turn.payload().get(key) instanceof String text && !text.isBlank()) return text;
            }
        }
        return null;
    }

    @NonNull List<TurnRecord> history() {
        return journal.snapshot();
    }

    @NonNull AgentResult failure(String message, boolean cancelled, String requestId) {
        String text = message == null ? "" : message;
        Map<String, Object> failure = new LinkedHashMap<>();
        failure.put("content", text);
        if (cancelled) failure.put("outcome", "CANCELLED");
        if (requestId != null) failure.put("requestId", requestId);
        appendTurn(new TurnRecord(nextTurn(), TurnType.EXECUTION_ERROR, failure, null));
        publishFrame(
                events.frame(EventFrame.Kind.ERROR)
                        .attr("turnNumber", turnNumber())
                        .text(text)
                        .build());
        return AgentResult.failure(text, Map.of("turns", turnNumber()));
    }

    void completed(RequestHandle task, @NonNull AgentResult result) {
        // Publish before completing the future, so awaiting clients observe the terminal event.
        publishFrame(
                events.frame(EventFrame.Kind.EPISODE_DONE)
                        .attr("requestId", task == null ? "" : task.episode.id())
                        .attr("turnNumber", turnNumber())
                        .attr("success", result.success())
                        .text(result.message())
                        .build());
    }

    void breaker(@NonNull RequestHandle task) {
        String notice = LoopBreaker.tripNotice(view.get().locale());
        emitMessage(notice);
        publishFrame(
                events.frame(EventFrame.Kind.BREAKER_TRIPPED)
                        .attr("turnNumber", turnNumber())
                        .attr("maxCallsPerEpisode", task.episode.breaker().maxCallsPerEpisode())
                        .text(notice)
                        .build());
    }

    void compacted(@NonNull String summary, int count) {
        emitMessage(Msg.get(view.get().locale(), "error.agent.compactDone", count));
        publishFrame(
                events.frame(EventFrame.Kind.COMPACTION)
                        .attr("turnNumber", turnNumber())
                        .attr("compactedTurns", count)
                        .text(summary)
                        .build());
    }

    @NonNull String failureMessage(@NonNull Throwable e) {
        var locale = view.get().locale();
        // Pre-pass: a locked vault wins over any wrapper (CredentialException nests it).
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof KeysteadVault.VaultLockedException) {
                return Msg.get(locale, "error.agent.vaultLocked");
            }
        }
        for (Throwable t = e; t != null; t = t.getCause()) {
            String detail = String.valueOf(t.getMessage());
            String message =
                    switch (t) {
                        case VetoRefusedException refused ->
                                Msg.get(
                                        locale,
                                        refused.approvalRequested
                                                ? "error.agent.approvalNotGranted"
                                                : "error.agent.vetoRefused");
                        case CredentialException ignored ->
                                Msg.get(locale, "error.agent.credentialMissing");
                        case LlmTimeoutException ignored ->
                                Msg.get(locale, "error.agent.llmTimeout");
                        case LlmRateLimitException ignored ->
                                Msg.get(locale, "error.agent.llmRateLimit");
                        case LlmAuthException ignored -> Msg.get(locale, "error.agent.llmAuth");
                        case ModelSchemaException ignored ->
                                Msg.get(locale, "error.agent.llmSchema", detail);
                        // The provider uses one exception type for transport and response parse
                        // failures.
                        case ModelCapabilityException ignored ->
                                detail.contains("could not be parsed")
                                        ? Msg.get(locale, "error.agent.llmParse")
                                        : Msg.get(locale, "error.agent.llmCallFailed", detail);
                        case IllegalStateException ignored when detail.contains("embed") ->
                                Msg.get(locale, "error.agent.embedFailed", detail);
                        default -> null;
                    };
            if (message != null) return message;
        }
        String detail = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        return Msg.get(locale, "error.agent.taskFailed", detail);
    }
}
