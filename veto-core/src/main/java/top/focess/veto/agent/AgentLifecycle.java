package top.focess.veto.agent;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.AgentRuntimeState.ActivatedObservation;
import top.focess.veto.agent.AgentRuntimeState.TaskCancellation;
import top.focess.veto.agent.AgentRuntimeState.VetoRefusedException;
import top.focess.veto.agent.AgentRuntimeState.WaitReason;
import top.focess.veto.agent.drift.ReadHistory;
import top.focess.veto.agent.identity.AgentPersona;
import top.focess.veto.agent.identity.Role;
import top.focess.veto.agent.loop.PromptCompiler;
import top.focess.veto.agent.tool.ToolCallContextHolder;
import top.focess.veto.agent.tool.ToolDefinition;
import top.focess.veto.api.agent.AgentAction;
import top.focess.veto.api.agent.AgentResult;
import top.focess.veto.api.agent.AgentState;
import top.focess.veto.api.llm.LlmBinding;
import top.focess.veto.api.llm.LlmSystemUsage;
import top.focess.veto.api.llm.ToolResultPresentationMode;
import top.focess.veto.api.llm.VetoRequest;
import top.focess.veto.api.llm.VetoResponse;
import top.focess.veto.api.llm.exceptions.CredentialException;
import top.focess.veto.api.llm.exceptions.LlmAuthException;
import top.focess.veto.api.llm.exceptions.LlmRateLimitException;
import top.focess.veto.api.llm.exceptions.LlmTimeoutException;
import top.focess.veto.api.llm.exceptions.ModelCapabilityException;
import top.focess.veto.api.llm.exceptions.ModelSchemaException;
import top.focess.veto.bus.DeltaFrame;
import top.focess.veto.i18n.Msg;
import top.focess.veto.integration.plugins.PluginLifecycleEvents;
import top.focess.veto.integration.plugins.SessionPlugins;
import top.focess.veto.model.tier.ModelTierRegistry;
import top.focess.veto.monitor.MonitorService;
import top.focess.veto.vault.KeysteadVault;

/** Owns request completion, cancellation, compaction and persona transitions. */
final class AgentLifecycle {
    private final @NonNull AgentRuntimeState runtime;

    AgentLifecycle(@NonNull AgentRuntimeState runtime) {
        this.runtime = runtime;
    }

    void saveExecutionWait(WaitReason reason) {
        runtime.executionWait = reason;
        if (reason == null) runtime.recoveredWait = false;
        notifyExecutionChanged();
    }

    String executionWaitReason() {
        WaitReason reason = runtime.executionWait;
        return reason == null ? null : reason.name();
    }

    void checkExecutionBoundary() {
        if (!runtime.sessionAlive) throw new CancellationException("Agent terminated");
        checkTaskCancellation();
    }

    void configurePlan(ModelTierRegistry registry, int maxSteps) {
        if (maxSteps < 1) throw new IllegalArgumentException("plan max-steps must be positive");
        runtime.maxPlanSteps = maxSteps;
        runtime.planTierRegistry = registry;
    }

    void checkTaskCancellation() {
        synchronized (runtime) {
            TaskCancellation task = runtime.activeCancellation;
            if (task != null && task.cancelled) {
                // Cancellation remains recorded on the task; cleanup must not inherit the signal
                // and close database sockets while persisting the cancelled outcome.
                AgentLifecycle.clearTaskInterrupt();
                throw new CancellationException("Task cancelled");
            }
        }
    }

    boolean cancelTask(@NonNull CompletableFuture<AgentResult> result, @NonNull Duration timeout)
            throws InterruptedException {
        TaskCancellation task;
        synchronized (runtime) {
            task = runtime.cancellableTasks.get(result);
            if (task == null) return result == runtime.lastExitedTask;
            if (!result.isDone()) task.cancelled = true;
            if (task.cancelled && task == runtime.activeCancellation && !task.interruptSent) {
                task.interruptSent = true;
                runtime.hitlRegistry.declineAll(runtime.agentId);
                Thread thread = runtime.runningThread;
                if (thread != null) thread.interrupt();
            }
        }
        try {
            return task.exited.get(Math.max(0, timeout.toNanos()), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            return false;
        } catch (ExecutionException e) {
            throw new IllegalStateException(e.getCause());
        }
    }

    @NonNull PluginContextSnapshot pluginContext() {
        var snapshot = runtime.lastPluginContext;
        return snapshot == null
                ? PluginContextSnapshot.from(runtime.persona.whitelistedTools(), false)
                : snapshot;
    }

    void setToolResultPresentation(@NonNull ToolResultPresentationMode toolResultPresentation) {
        runtime.toolResultPresentation = toolResultPresentation;
    }

    void processUserPrompt(@NonNull String prompt) {
        prompt =
                runtime.hooks()
                        .captureUserPrompt(
                                runtime.hooks()
                                        .workflow(
                                                prompt,
                                                (hook, text) ->
                                                        hook.beforeInput(
                                                                runtime.hooks().workflowContext(),
                                                                text)));
        if (runtime.recoveredWait) {
            runtime.output()
                    .appendTurn(
                            new TurnRecord(
                                    ++runtime.turnNumber,
                                    TurnType.EXECUTION_ERROR,
                                    Map.of(
                                            "outcome",
                                            "INTERRUPTED",
                                            "content",
                                            "The previous execution was interrupted by a backend restart."
                                                    + " Its uncompleted plans are not pending; tool effects"
                                                    + " without recorded results remain unknown."),
                                    null));
        }
        runtime.completionToolFinished = false;
        runtime.pendingResponse = null;
        runtime.submissionRequest = null;
        runtime.declinedCallSignatures.clear();
        // Actively tell the agent about background tasks that ended since it last ran — drained
        // into the context BEFORE the new user prompt so the model reads them together. This is
        // the push half of the task lifecycle (the UI gets TASK_EXITED live; the agent gets it
        // here on its next turn instead of having to remember to poll view_task).
        runtime.monitor().injectPendingTaskExitNotices();
        // Fresh UserPromptAction: reset plan state and program counter. An exact "continue" has
        // special semantics only immediately after a breaker trip. Preserve the literal user input
        // in history while attaching the prior task for prompt compilation; otherwise a long,
        // budget-trimmed episode re-anchors on the context-free word "continue".
        String resumeContext =
                runtime.awaitingBreakerContinuation && "continue".equalsIgnoreCase(prompt.strip())
                        ? (runtime.activeUserTask.isBlank()
                                ? latestUserTaskContext()
                                : runtime.activeUserTask)
                        : null;
        runtime.monitor().rememberRequest();
        runtime.activeMonitorEventId = null;
        if (resumeContext == null || runtime.activeRequestId == null)
            runtime.activeRequestId = UUID.randomUUID().toString();
        runtime.activeUserTask = resumeContext != null ? resumeContext : prompt;
        saveExecutionWait(null);
        runtime.monitor().injectMonitorEvents();
        runtime.awaitingBreakerContinuation = false;
        runtime.models().refreshSystemHistory();
        TurnRecord prospectiveUserTurn =
                resumeContext != null
                        ? TurnRecord.breakerContinuation(
                                runtime.turnNumber + 1, prompt, resumeContext)
                        : TurnRecord.userPrompt(runtime.turnNumber + 1, prompt);
        List<TurnRecord> prospectiveHistory;
        synchronized (runtime) {
            prospectiveHistory = new ArrayList<>(runtime.output().history());
        }
        prospectiveUserTurn = withRequestId(prospectiveUserTurn);
        prospectiveHistory.add(prospectiveUserTurn);
        runtime.preparedFirstPrompt = runtime.models().compilePrompt(prospectiveHistory, false);
        runtime.output()
                .appendTurn(
                        runtime.lifecycle()
                                .withRequestId(
                                        resumeContext != null
                                                ? TurnRecord.breakerContinuation(
                                                        ++runtime.turnNumber, prompt, resumeContext)
                                                : TurnRecord.userPrompt(
                                                        ++runtime.turnNumber, prompt)));
        TaskCancellation cancellation = runtime.activeCancellation;
        if (cancellation != null) cancellation.requestId = runtime.activeRequestId;
        runtime.program = null;
        runtime.breaker.newEpisode();

        runtime.models().runAutonomous();
    }

    @NonNull TurnRecord withRequestId(@NonNull TurnRecord turn) {
        Map<String, Object> payload = new LinkedHashMap<>(turn.payload());
        String requestId = runtime.activeRequestId;
        if (requestId == null) throw new IllegalStateException("User request identity is missing");
        payload.put("requestId", requestId);
        return new TurnRecord(turn.turnNumber(), turn.type(), payload, turn.timestamp());
    }

    String latestUserTaskContext() {
        List<TurnRecord> history = runtime.output().history();
        for (int i = history.size() - 1; i >= 0; i--) {
            TurnRecord turn = history.get(i);
            if (turn.type() != TurnType.USER_PROMPT) {
                continue;
            }
            Object resumed = turn.payload().get("resume_context");
            if (resumed instanceof String text && !text.isBlank()) {
                return text;
            }
            Object content = turn.payload().get("content");
            if (content instanceof String text && !text.isBlank()) {
                return text;
            }
        }
        return null;
    }

    void processCompaction() {
        int lastInitIndex = -1;
        List<TurnRecord> history = runtime.output().history();
        for (int i = history.size() - 1; i >= 0; i--) {
            if (history.get(i).type() == TurnType.AGENT_INIT) {
                lastInitIndex = i;
                break;
            }
        }
        int anchorIndex = lastInitIndex != -1 ? lastInitIndex : 0;

        List<TurnRecord> workTurns = new ArrayList<>();
        if (anchorIndex >= history.size() - 1) {
            runtime.output().emitMessage(Msg.get(runtime.locale, "error.agent.compactNothing"));
            return;
        }
        for (int i = anchorIndex + 1; i < history.size(); i++) {
            workTurns.add(history.get(i));
        }

        String finalSummary = computeCompactionSummary(workTurns);
        if ("{}".equals(finalSummary)) {
            runtime.output()
                    .appendObservation(
                            "compaction_failed",
                            "No valid summary was produced; the context was retained.");
            return;
        }

        runtime.output().appendTurn(TurnRecord.rewind(++runtime.turnNumber, 0));
        runtime.models().appendAgentInit(runtime.models().linkCurrentSystemMessage());
        runtime.output()
                .appendTurn(TurnRecord.compactionSummary(++runtime.turnNumber, finalSummary));
        runtime.output()
                .emitMessage(Msg.get(runtime.locale, "error.agent.compactDone", workTurns.size()));
        // Domain event: the session compacted. Subscribers can mark the ledger boundary without
        // inferring it from the message text.
        runtime.output()
                .publishFrame(
                        DeltaFrame.builder()
                                .sessionId(runtime.sessionId)
                                .kind(DeltaFrame.Kind.COMPACTION)
                                .attr("turnNumber", runtime.turnNumber)
                                .attr("compactedTurns", workTurns.size())
                                .text(finalSummary)
                                .build());
    }

    @NonNull String computeCompactionSummary(@NonNull List<TurnRecord> workTurns) {
        return new HistoryCompactor(
                        runtime.objectMapper,
                        (system, user) ->
                                runtime.models().requests().compactionRequest(system, user),
                        this::performCompactionCall)
                .summarize(workTurns);
    }

    static void clearTaskInterrupt() {
        if (Thread.interrupted()) {
            AgentRuntimeState.log.debug("Cleared task interrupt before lifecycle cleanup");
        }
    }

    @NonNull VetoResponse performCompactionCall(@NonNull VetoRequest request) {
        VetoResponse response;
        LlmSystemUsage.begin();
        try {
            checkTaskCancellation();
            response = runtime.hooks().callModelWithHooks(request);
            checkTaskCancellation();
        } finally {
            for (LlmSystemUsage.Usage measured : LlmSystemUsage.drain()) {
                UsageMeasurement data =
                        UsageMeasurement.measured(request, measured).forCompaction();
                runtime.output().recordUsage(runtime.turnNumber, data);
            }
        }
        return response;
    }

    void completeSuccess() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("turns", runtime.turnNumber);
        complete(AgentResult.success(runtime.lastMessage, meta));
    }

    void completeBreaker() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("breakerTrip", true);
        meta.put("turns", runtime.turnNumber);
        complete(AgentResult.failure(runtime.lastMessage, meta));
    }

    void completeFailure(String message) {
        completeFailure(message, false, runtime.activeRequestId);
    }

    void completeFailure(String message, boolean cancelled, String request) {
        Map<String, Object> failure = new LinkedHashMap<>();
        failure.put("content", message == null ? "" : message);
        if (cancelled) failure.put("outcome", "CANCELLED");
        if (request != null) failure.put("requestId", request);
        runtime.output()
                .appendTurn(
                        new TurnRecord(
                                ++runtime.turnNumber, TurnType.EXECUTION_ERROR, failure, null));
        // Domain event: the episode failed. Subscribers that surface an error banner use this; the
        // EPISODE_DONE below (success=false) is the authoritative "stop waiting" signal.
        runtime.output()
                .publishFrame(
                        DeltaFrame.builder()
                                .sessionId(runtime.sessionId)
                                .kind(DeltaFrame.Kind.ERROR)
                                .attr("turnNumber", runtime.turnNumber)
                                .text(message == null ? "" : message)
                                .build());
        Map<String, Object> meta = new HashMap<>();
        meta.put("turns", runtime.turnNumber);
        complete(AgentResult.failure(message == null ? "" : message, meta));
    }

    @NonNull String failureMessage(@NonNull Throwable e) {
        // Pre-pass: a locked vault wins over any wrapper (CredentialException nests it).
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof KeysteadVault.VaultLockedException) {
                return Msg.get(runtime.locale, "error.agent.vaultLocked");
            }
        }
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof VetoRefusedException refused) {
                return Msg.get(
                        runtime.locale,
                        refused.approvalRequested
                                ? "error.agent.approvalNotGranted"
                                : "error.agent.vetoRefused");
            }
            if (t instanceof CredentialException) {
                return Msg.get(runtime.locale, "error.agent.credentialMissing");
            }
            if (t instanceof LlmTimeoutException) {
                return Msg.get(runtime.locale, "error.agent.llmTimeout");
            }
            if (t instanceof LlmRateLimitException) {
                return Msg.get(runtime.locale, "error.agent.llmRateLimit");
            }
            if (t instanceof LlmAuthException) {
                return Msg.get(runtime.locale, "error.agent.llmAuth");
            }
            if (t instanceof ModelSchemaException) {
                return Msg.get(
                        runtime.locale, "error.agent.llmSchema", String.valueOf(t.getMessage()));
            }
            if (t instanceof ModelCapabilityException mce) {
                // The same type covers transport call failures and unparseable responses
                // (AbstractLlmProvider); discriminate on the fixed message prefix.
                String detail = String.valueOf(mce.getMessage());
                if (detail.contains("could not be parsed")) {
                    return Msg.get(runtime.locale, "error.agent.llmParse");
                }
                return Msg.get(runtime.locale, "error.agent.llmCallFailed", detail);
            }
            if (t instanceof IllegalStateException
                    && String.valueOf(t.getMessage()).contains("embed")) {
                // ProviderEmbedder failures surface as IllegalStateException (best-effort memory).
                return Msg.get(
                        runtime.locale, "error.agent.embedFailed", String.valueOf(t.getMessage()));
            }
        }
        String detail = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        return Msg.get(runtime.locale, "error.agent.taskFailed", detail);
    }

    void complete(@NonNull AgentResult result) {
        Consumer<AgentResult> cb;
        synchronized (runtime) {
            TaskCancellation task = runtime.activeCancellation;
            if (task != null && task.cancelled)
                result = AgentResult.failure("Task cancelled", Map.of());
            runtime.monitor().rememberRequest();
            runtime.waitingForMonitor = false;
            MonitorService monitors = runtime.monitorService;
            if (monitors != null) {
                for (var entry : List.copyOf(runtime.activatedMonitorEvents.entrySet())) {
                    ActivatedObservation observation = entry.getValue();
                    if (Objects.equals(observation.requestId(), runtime.activeRequestId)) {
                        monitors.activationCompleted(
                                runtime.agentId, observation.event(), result.success());
                        runtime.activatedMonitorEvents.remove(entry.getKey());
                    }
                }
            }
            // Domain event: the episode finished. Carries the authoritative success flag so
            // subscribers
            // (the web UI, the terminal adapter) can stop waiting on the episode without blocking
            // on
            // the
            // submit call. Emitted before the future completes so a subscriber that also awaits the
            // future sees the event first.
            runtime.output()
                    .publishFrame(
                            DeltaFrame.builder()
                                    .sessionId(runtime.sessionId)
                                    .kind(DeltaFrame.Kind.EPISODE_DONE)
                                    .attr(
                                            "requestId",
                                            runtime.activeRequestId == null
                                                    ? ""
                                                    : runtime.activeRequestId)
                                    .attr("turnNumber", runtime.turnNumber)
                                    .attr("success", result.success())
                                    .text(result.message())
                                    .build());
            runtime.actionQueue.addAll(runtime.deferredUserPrompts);
            runtime.deferredUserPrompts.clear();
            // Complete the in-place handoff future installed by startTask. Completing the field
            // (rather than reassigning it to a fresh completed future) means an await that already
            // snapshotted resultFuture blocks on the right future and wakes here — a reassignment
            // would leave await holding a stale (already-completed-null) snapshot that returned
            // null.
            if (runtime.handlingDirectUserPrompt) return;
            CompletableFuture<AgentResult> completion = runtime.monitorResultFuture;
            (completion != null ? completion : task != null ? task.result : runtime.resultFuture)
                    .complete(result);
            cb =
                    completion != null
                            ? runtime.monitorCallback
                            : task != null ? task.callback : runtime.callback;
        }
        if (cb != null) {
            cb.accept(result);
        }
    }

    void transitionTo(@NonNull AgentState next) {
        if (next == AgentState.INTERCEPTED) saveExecutionWait(WaitReason.APPROVAL);
        if (runtime.state == next) return;
        runtime.state = next;
        notifyExecutionChanged();
    }

    void notifyExecutionChanged() {
        runtime.output()
                .publishFrame(
                        DeltaFrame.builder()
                                .sessionId(runtime.sessionId)
                                .kind(DeltaFrame.Kind.SESSION_INVALIDATED)
                                .attr("agentId", runtime.agentId)
                                .attr(
                                        "resources",
                                        runtime.objectMapper
                                                .createArrayNode()
                                                .add("agents")
                                                .add("execution"))
                                .build());
    }

    boolean hasPendingWork() {
        if (!runtime.sessionAlive || runtime.state == AgentState.TERMINATED) return false;
        return runtime.state != AgentState.IDLE
                || runtime.actionQueue.stream()
                        .anyMatch(
                                action ->
                                        action instanceof AgentAction.UserPromptAction
                                                || action
                                                        instanceof
                                                        AgentAction.DirectUserPromptAction
                                                || action instanceof AgentAction.CompactAction);
    }

    void startTask(Consumer<AgentResult> callback, @NonNull AgentAction action) {
        synchronized (runtime) {
            if (!runtime.sessionAlive) throw new IllegalStateException("Agent has terminated");
            if (action instanceof AgentAction.UserPromptAction prompt)
                action =
                        new AgentAction.UserPromptAction(
                                runtime.hooks().captureUserPrompt(prompt.prompt()));
            runtime.callback = callback;
            runtime.resultFuture = new CompletableFuture<>();
            if (action instanceof AgentAction.UserPromptAction) {
                TaskCancellation task = new TaskCancellation(runtime.resultFuture, callback);
                runtime.taskActions.put(action, task);
                runtime.cancellableTasks.put(runtime.resultFuture, task);
            }
            if (!runtime.sessionAlive) {
                runtime.resultFuture.complete(
                        AgentResult.failure(
                                Msg.get(runtime.locale, "error.agent.interrupted"), Map.of()));
                return;
            }
            if (runtime.state == AgentState.INTERCEPTED) {
                runtime.hitlRegistry.declineAll(runtime.agentId);
            }
            runtime.actionQueue.add(action);
            notifyExecutionChanged();
        }
    }

    void enqueue(@NonNull AgentAction action) {
        if (action instanceof AgentAction.DirectUserPromptAction prompt) {
            synchronized (runtime) {
                runtime.actionQueue.add(
                        new AgentAction.DirectUserPromptAction(
                                runtime.hooks().captureUserPrompt(prompt.prompt())));
                notifyExecutionChanged();
            }
            return;
        }
        runtime.actionQueue.add(action);
        notifyExecutionChanged();
    }

    void bind(@NonNull LlmBinding binding) {
        runtime.binding = binding;
    }

    @NonNull LlmBinding binding() {
        return runtime.binding;
    }

    @NonNull AgentResult await(@NonNull Duration timeout)
            throws TimeoutException, InterruptedException {
        CompletableFuture<AgentResult> f = runtime.resultFuture;
        try {
            return f.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            // The runner completes the future normally via complete (never exceptionally); an
            // exceptional completion here is unexpected — surface its cause.
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new RuntimeException("Agent task completed exceptionally", cause);
        }
    }

    @NonNull CompletableFuture<AgentResult> result() {
        return runtime.resultFuture;
    }

    @NonNull AgentState state() {
        if (runtime.recoveredWait && runtime.state != AgentState.TERMINATED)
            return AgentState.WAITING;
        return runtime.state;
    }

    @NonNull ReadHistory readHistory() {
        return runtime.readHistory;
    }

    @NonNull Set<String> whitelistedToolsView() {
        return runtime.whitelistedTools;
    }

    void setCompletionTool(@NonNull String toolName) {
        if (!runtime.whitelistedTools.contains(toolName))
            throw new IllegalArgumentException("Completion tool must be in the agent's whitelist");
        runtime.completionTool = toolName;
        runtime.completionToolFinished = false;
    }

    @NonNull String agentId() {
        return runtime.agentId;
    }

    UUID groupId() {
        return runtime.groupId;
    }

    void setGroupId(UUID groupId) {
        runtime.groupId = groupId;
    }

    void restoreLeader(
            @NonNull UUID restoredGroup,
            @NonNull LlmBinding leaderBinding,
            @NonNull Set<ToolDefinition> tools) {
        synchronized (runtime) {
            if (restoredGroup.equals(runtime.groupId)) {
                bind(leaderBinding);
                return;
            }
            if (hasPendingWork()) throw new IllegalStateException("Cannot restore a busy Agent");
            runtime.preTransformPersona = runtime.persona;
            runtime.preTransformBinding = runtime.binding;
            applyPersona(runtime.persona.withRoleAndTools(Role.LEADER, tools));
            bind(leaderBinding);
            setGroupId(restoredGroup);
        }
    }

    void setOwner(String owner) {
        runtime.owner = owner;
    }

    void setLocale(Locale locale) {
        runtime.locale = locale != null ? locale : Locale.ENGLISH;
        runtime.hitlRegistry.setLocale(runtime.agentId, runtime.locale);
    }

    @NonNull Locale locale() {
        return runtime.locale;
    }

    void setSessionId(@NonNull UUID sessionId) {
        runtime.sessionId = sessionId;
        runtime.hitlRegistry.setSession(runtime.agentId, sessionId);
    }

    @NonNull AgentPersona personaView() {
        return runtime.persona;
    }

    void attachSessionPlugins(@NonNull SessionPlugins value) {
        runtime.sessionPlugins = value;
    }

    void applyPersona(@NonNull AgentPersona persona) {
        var selection = runtime.sessionPlugins;
        if (selection != null)
            persona =
                    persona.withWhitelistedTools(
                            selection.tools(
                                    runtime.sessionId.toString(), persona.whitelistedTools()));
        runtime.persona = persona;
        runtime.whitelistedTools =
                persona.whitelistedTools().stream()
                        .map(ToolDefinition::name)
                        .collect(Collectors.toUnmodifiableSet());
        notifyExecutionChanged();
    }

    void transformToLeader(ToolCallContextHolder.@NonNull TransformDirective directive) {
        String summary = summarizeForRoleChange();

        // Stash the pre-transform STANDALONE persona + binding so disband_group can restore them,
        // then adopt the Leader persona + tool set + top-tier binding + group stamp.
        runtime.preTransformPersona = runtime.persona;
        runtime.preTransformBinding = runtime.binding;
        runtime.lifecycle()
                .applyPersona(
                        runtime.persona.withRoleAndTools(Role.LEADER, directive.leaderTools()));
        bind(directive.leaderBinding());
        setGroupId(directive.groupId());

        restartAfterRoleChange(summary, "runtime-leader", directive.brief());
        AgentRuntimeState.log.info(
                "Agent {} transformed into Leader of group {} (Leader model={})",
                runtime.agentId,
                directive.groupId(),
                directive.leaderBinding().model());
    }

    void transformToStandalone(@NonNull String brief) {
        String summary = summarizeForRoleChange();

        // Restore the stashed STANDALONE persona + binding. Null-safe: if no transform was stashed
        // (the agent never led a group), flip the role back to STANDALONE on the current persona.
        AgentPersona stashedPersona = runtime.preTransformPersona;
        AgentPersona restored =
                stashedPersona != null ? stashedPersona : runtime.persona.withRole(Role.STANDALONE);
        LlmBinding stashedBinding = runtime.preTransformBinding;
        LlmBinding restoredBinding = stashedBinding != null ? stashedBinding : runtime.binding;
        applyPersona(restored);
        bind(restoredBinding);
        setGroupId(null);
        runtime.preTransformPersona = null;
        runtime.preTransformBinding = null;

        restartAfterRoleChange(summary, "runtime-disband", brief);
        AgentRuntimeState.log.info(
                "Agent {} reversed transform back to STANDALONE (group disbanded)",
                runtime.agentId);
    }

    @NonNull String summarizeForRoleChange() {
        try {
            return computeCompactionSummary(runtime.output().history());
        } catch (RuntimeException error) {
            AgentRuntimeState.log.warn(
                    "Agent {} role-change compaction failed; preserving original history",
                    runtime.agentId,
                    error);
            return "{}";
        }
    }

    void restartAfterRoleChange(
            @NonNull String summary, @NonNull String prompt, @NonNull String brief) {
        if (!summary.isBlank() && !"{}".equals(summary)) {
            runtime.output().appendTurn(TurnRecord.rewind(++runtime.turnNumber, 0));
            runtime.models().appendAgentInit(runtime.models().linkCurrentSystemMessage());
            runtime.output()
                    .appendTurn(TurnRecord.compactionSummary(++runtime.turnNumber, summary));
        } else {
            runtime.models().rewindAndRestoreHistory();
        }
        runtime.output()
                .appendTurn(
                        PromptCompiler.sourcedUserPrompt(
                                ++runtime.turnNumber,
                                prompt,
                                Map.of("task", runtime.activeUserTask, "brief", brief)));
        runtime.program = null;
        runtime.breaker.newEpisode();
    }

    void onTermination(@NonNull Runnable callback) {
        runtime.terminationCallback = callback;
    }

    @NonNull UUID sessionId() {
        return runtime.sessionId;
    }

    void notifyTermination() {
        Runnable callback = runtime.terminationCallback;
        if (callback != null) callback.run();
    }

    void terminate() {
        synchronized (runtime) {
            runtime.sessionAlive = false;
            var events = runtime.lifecycleEvents;
            String currentOwner = runtime.owner;
            if (events != null && currentOwner != null)
                events.agentTerminated(currentOwner, runtime.sessionId.toString(), runtime.agentId);
        }
        transitionTo(AgentState.TERMINATED);
        runtime.resultFuture.complete(
                AgentResult.failure(Msg.get(runtime.locale, "error.agent.interrupted"), Map.of()));
        runtime.hitlRegistry.clear(runtime.agentId);
        Thread thread = runtime.runningThread;
        if (thread != null && thread != Thread.currentThread()) thread.interrupt();
        notifyTermination();
    }

    void attachLifecycleEvents(@NonNull PluginLifecycleEvents events) {
        runtime.lifecycleEvents = events;
    }
}
