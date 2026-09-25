package top.focess.veto.agent;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
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
import org.jspecify.annotations.Nullable;
import top.focess.veto.agent.AgentRuntimeState.ActivatedObservation;
import top.focess.veto.agent.AgentRuntimeState.VetoRefusedException;
import top.focess.veto.agent.ExecutionControl.Wait;
import top.focess.veto.agent.drift.ReadHistory;
import top.focess.veto.agent.identity.AgentPersona;
import top.focess.veto.agent.loop.LoopBreaker;
import top.focess.veto.agent.loop.PromptCompiler;
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
import top.focess.veto.api.plugin.agent.AgentProfile;
import top.focess.veto.api.plugin.contract.AgentWorkSource;
import top.focess.veto.api.plugin.contract.JsonValues;
import top.focess.veto.bus.DeltaFrame;
import top.focess.veto.i18n.Msg;
import top.focess.veto.integration.plugins.PluginLifecycleEvents;
import top.focess.veto.integration.plugins.SessionPlugins;
import top.focess.veto.model.tier.ModelTierRegistry;
import top.focess.veto.util.Nullness;
import top.focess.veto.vault.KeysteadVault;

/** Owns request completion, cancellation, compaction and persona transitions. */
final class AgentLifecycle {
    private final @NonNull AgentRuntimeState runtime;

    AgentLifecycle(@NonNull AgentRuntimeState runtime) {
        this.runtime = runtime;
    }

    void saveExecutionWait(Wait reason) {
        synchronized (runtime) {
            if (!runtime.control.open()) return;
            var waits =
                    runtime.control instanceof ExecutionControl.Suspended suspended
                            ? new HashSet<>(suspended.waits())
                            : new HashSet<Wait>();
            var activity =
                    runtime.control instanceof ExecutionControl.Executing executing
                            ? executing.activity()
                            : runtime.control instanceof ExecutionControl.Suspended suspended
                                    ? suspended.activity()
                                    : ExecutionControl.Activity.MODEL;
            if (reason == null) {
                waits.removeAll(
                        Set.of(Wait.APPROVAL, Wait.QUESTION, Wait.BREAKER, Wait.INTERRUPTED));
            } else waits.add(reason);
            runtime.control =
                    waits.isEmpty()
                            ? new ExecutionControl.Executing(runtime.control.request(), activity)
                            : new ExecutionControl.Suspended(
                                    runtime.control.request(), activity, waits);
        }
        notifyExecutionChanged();
    }

    void clearWait(@NonNull Wait reason) {
        synchronized (runtime) {
            if (!(runtime.control instanceof ExecutionControl.Suspended suspended)) return;
            var waits = new HashSet<>(suspended.waits());
            waits.remove(reason);
            runtime.control =
                    waits.isEmpty()
                            ? new ExecutionControl.Executing(
                                    suspended.request(), suspended.activity())
                            : new ExecutionControl.Suspended(
                                    suspended.request(), suspended.activity(), waits);
        }
    }

    String executionWaitReason() {
        for (Wait reason : List.of(Wait.APPROVAL, Wait.QUESTION, Wait.BREAKER))
            if (runtime.control.waiting(reason)) return reason.name();
        return null;
    }

    void checkExecutionBoundary() {
        runtime.executionPolicy.check().run();
        if (!runtime.control.open()) throw new CancellationException("Agent terminated");
        checkTaskCancellation();
        RequestHandle request = runtime.control.request();
        if (request != null) request.awaiting();
    }

    void configureModelTiers(ModelTierRegistry registry) {
        runtime.modelTierRegistry = registry;
    }

    @NonNull RequestHandle currentRequest() {
        return Nullness.requireNonNull(runtime.control.request(), "No executing request");
    }

    void checkTaskCancellation() {
        synchronized (runtime) {
            RequestHandle task = runtime.control.request();
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
        RequestHandle task;
        synchronized (runtime) {
            if (!(result instanceof RequestHandle.Result owned) || owned.handle().owner != runtime)
                return false;
            task = owned.handle();
            if (!result.isDone()) task.cancelled = true;
            if (task.cancelled && task != runtime.control.request()) {
                runtime.actionQueue.removeIf(queued -> queued.handle() == task);
                runtime.deferredUserPrompts.removeIf(queued -> queued.handle() == task);
                task.result.complete(AgentResult.failure("Task cancelled", Map.of()));
                task.settled.complete(true);
            }
            if (task.cancelled
                    && task == runtime.control.request()
                    && runtime.control.waiting(Wait.PLUGIN)) {
                completeFailure("Task cancelled", true, task.requestId);
                runtime.control = runtime.control.withRequest(null);
                task.settled.complete(true);
                transitionTo(AgentState.IDLE);
            }
            if (task.cancelled && task == runtime.control.request() && !task.interruptSent) {
                task.interruptSent = true;
                runtime.toolBoundary.declineAll();
                Thread thread = runtime.runningThread;
                if (thread != null) thread.interrupt();
            }
        }
        try {
            return task.settled.get(Math.max(0, timeout.toNanos()), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            return false;
        } catch (ExecutionException e) {
            throw new IllegalStateException(e.getCause());
        }
    }

    @NonNull PluginContextSnapshot pluginContext() {
        return runtime.models().pluginContext();
    }

    void setToolResultPresentation(@NonNull ToolResultPresentationMode toolResultPresentation) {
        runtime.toolResultPresentation = toolResultPresentation;
    }

    ModelSession.@NonNull Prepared processUserPrompt(@NonNull String prompt) {
        runtime.continuations().remember(currentRequest().episode);
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
        if (runtime.control.waiting(Wait.INTERRUPTED)) {
            runtime.output()
                    .appendTurn(
                            new TurnRecord(
                                    runtime.output().nextTurn(),
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

        currentRequest().declinedCallSignatures.clear();
        // Drain plugin observations into the context before the new user prompt so the model
        // reads them together. Plugins publish any live UI updates through generic plugin events.
        // Fresh UserPromptAction: reset plan state and program counter. An exact "continue" has
        // special semantics only immediately after a breaker trip. Preserve the literal user input
        // in history while attaching the prior task for prompt compilation; otherwise a long,
        // budget-trimmed episode re-anchors on the context-free word "continue".
        String resumeContext =
                runtime.control.waiting(Wait.BREAKER) && "continue".equalsIgnoreCase(prompt.strip())
                        ? (runtime.currentTask().isBlank()
                                ? latestUserTaskContext()
                                : runtime.currentTask())
                        : null;
        currentRequest().episode.task(resumeContext != null ? resumeContext : prompt);
        currentRequest().episode.observationId(null);
        saveExecutionWait(null);
        runtime.continuations().injectObservations();

        runtime.models().refreshSystemHistory();
        TurnRecord prospectiveUserTurn =
                resumeContext != null
                        ? TurnRecord.breakerContinuation(
                                runtime.output().turnNumber() + 1, prompt, resumeContext)
                        : TurnRecord.userPrompt(runtime.output().turnNumber() + 1, prompt);
        List<TurnRecord> prospectiveHistory;
        synchronized (runtime) {
            prospectiveHistory = new ArrayList<>(runtime.output().history());
        }
        prospectiveUserTurn = withRequestId(prospectiveUserTurn);
        prospectiveHistory.add(prospectiveUserTurn);
        var firstPrompt = runtime.models().preparePrompt(prospectiveHistory, false);
        runtime.output()
                .appendTurn(
                        runtime.lifecycle()
                                .withRequestId(
                                        resumeContext != null
                                                ? TurnRecord.breakerContinuation(
                                                        runtime.output().nextTurn(),
                                                        prompt,
                                                        resumeContext)
                                                : TurnRecord.userPrompt(
                                                        runtime.output().nextTurn(), prompt)));
        RequestHandle cancellation = runtime.control.request();
        if (resumeContext != null) currentRequest().episode.breaker().grantContinuation();
        runtime.continuations().persistRequest();

        return firstPrompt;
    }

    @NonNull TurnRecord withRequestId(@NonNull TurnRecord turn) {
        Map<String, Object> payload = new LinkedHashMap<>(turn.payload());
        String requestId = currentRequest().episode.id();
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

        runtime.output().appendTurn(TurnRecord.rewind(runtime.output().nextTurn(), 0));
        runtime.models().appendAgentInit(runtime.models().linkCurrentSystemMessage());
        runtime.output()
                .appendTurn(
                        TurnRecord.compactionSummary(runtime.output().nextTurn(), finalSummary));
        runtime.output()
                .emitMessage(Msg.get(runtime.locale, "error.agent.compactDone", workTurns.size()));
        // Domain event: the session compacted. Subscribers can mark the ledger boundary without
        // inferring it from the message text.
        runtime.output()
                .publishFrame(
                        DeltaFrame.builder()
                                .sessionId(runtime.sessionId)
                                .kind(DeltaFrame.Kind.COMPACTION)
                                .attr("turnNumber", runtime.output().turnNumber())
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
                runtime.output().recordUsage(runtime.output().turnNumber(), data);
            }
        }
        return response;
    }

    void completeSuccess() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("turns", runtime.output().turnNumber());
        complete(AgentResult.success(currentRequest().message, meta));
    }

    void completeBreaker() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("breakerTrip", true);
        meta.put("turns", runtime.output().turnNumber());
        complete(AgentResult.failure(currentRequest().message, meta));
    }

    void tripBreaker() {
        saveExecutionWait(Wait.BREAKER);
        String notice = LoopBreaker.tripNotice(runtime.locale);
        runtime.output().emitMessage(notice);
        runtime.output()
                .publishFrame(
                        DeltaFrame.builder()
                                .sessionId(runtime.sessionId)
                                .kind(DeltaFrame.Kind.BREAKER_TRIPPED)
                                .attr("turnNumber", runtime.output().turnNumber())
                                .attr(
                                        "maxCallsPerEpisode",
                                        currentRequest().episode.breaker().maxCallsPerEpisode())
                                .text(notice)
                                .build());
    }

    void completeFailure(String message) {
        RequestHandle request = runtime.control.request();
        completeFailure(message, false, request == null ? null : request.episode.id());
    }

    void completeFailure(String message, boolean cancelled, String request) {
        Map<String, Object> failure = new LinkedHashMap<>();
        failure.put("content", message == null ? "" : message);
        if (cancelled) failure.put("outcome", "CANCELLED");
        if (request != null) failure.put("requestId", request);
        runtime.output()
                .appendTurn(
                        new TurnRecord(
                                runtime.output().nextTurn(),
                                TurnType.EXECUTION_ERROR,
                                failure,
                                null));
        // Domain event: the episode failed. Subscribers that surface an error banner use this; the
        // EPISODE_DONE below (success=false) is the authoritative "stop waiting" signal.
        runtime.output()
                .publishFrame(
                        DeltaFrame.builder()
                                .sessionId(runtime.sessionId)
                                .kind(DeltaFrame.Kind.ERROR)
                                .attr("turnNumber", runtime.output().turnNumber())
                                .text(message == null ? "" : message)
                                .build());
        Map<String, Object> meta = new HashMap<>();
        meta.put("turns", runtime.output().turnNumber());
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
        RequestHandle task;
        synchronized (runtime) {
            task = runtime.control.request();
            if (task != null && task.cancelled)
                result = AgentResult.failure("Task cancelled", Map.of());
            runtime.lifecycle().clearWait(Wait.PLUGIN);
            AgentWorkSource source = runtime.continuations().source();
            if (source != null) {
                for (var entry : List.copyOf(runtime.activatedObservations.entrySet())) {
                    ActivatedObservation observation = entry.getValue();
                    if (task != null
                            && Objects.equals(observation.requestId(), task.episode.id())) {
                        try {
                            source.completed(
                                    runtime.continuations().scope(),
                                    observation.event(),
                                    result.success());
                            runtime.activatedObservations.remove(entry.getKey());
                        } catch (RuntimeException error) {
                            AgentRuntimeState.log.warn(
                                    "Plugin completion remains unacknowledged", error);
                        }
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
                                    .attr("requestId", task == null ? "" : task.episode.id())
                                    .attr("turnNumber", runtime.output().turnNumber())
                                    .attr("success", result.success())
                                    .text(result.message())
                                    .build());
            runtime.actionQueue.addAll(runtime.deferredUserPrompts);
            runtime.deferredUserPrompts.clear();
        }
        if (task != null) {
            runtime.continuations().settled(task.episode);
            task.releaseWaits();
            task.result.complete(result);
        }
    }

    void transitionTo(@NonNull AgentState next) {
        synchronized (runtime) {
            if (!runtime.control.open()) return;
            if (next == AgentState.INTERCEPTED) {
                saveExecutionWait(Wait.APPROVAL);
                return;
            }
            if (next == AgentState.PAUSED) {
                saveExecutionWait(Wait.PAUSE);
                return;
            }
            if (next == AgentState.TERMINATED) {
                runtime.control =
                        new ExecutionControl.Closed(ExecutionControl.CloseReason.AGENT_DELETED);
            } else if (!(runtime.control instanceof ExecutionControl.Suspended)) {
                runtime.control =
                        next == AgentState.IDLE && runtime.control.request() == null
                                ? new ExecutionControl.Idle()
                                : new ExecutionControl.Executing(
                                        runtime.control.request(),
                                        next == AgentState.WAITING
                                                ? ExecutionControl.Activity.TOOL
                                                : ExecutionControl.Activity.MODEL);
            }
        }
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
        if (!runtime.control.open() || runtime.control.state() == AgentState.TERMINATED)
            return false;
        return runtime.control.state() != AgentState.IDLE
                || runtime.actionQueue.stream()
                        .anyMatch(
                                action ->
                                        action.action() instanceof AgentAction.UserPromptAction
                                                || action.action()
                                                        instanceof
                                                        AgentAction.DirectUserPromptAction
                                                || action.action()
                                                        instanceof AgentAction.CompactAction);
    }

    @NonNull RequestHandle startTask(Consumer<AgentResult> callback, @NonNull AgentAction action) {
        synchronized (runtime) {
            if (!runtime.control.open()) throw new IllegalStateException("Agent has terminated");
            if (action instanceof AgentAction.UserPromptAction prompt)
                action =
                        new AgentAction.UserPromptAction(
                                runtime.hooks().captureUserPrompt(prompt.prompt()));
            if (action instanceof AgentAction.DirectUserPromptAction prompt)
                action =
                        new AgentAction.DirectUserPromptAction(
                                runtime.hooks().captureUserPrompt(prompt.prompt()));
            RequestHandle previous = runtime.control.request();
            String promptText =
                    action instanceof AgentAction.UserPromptAction prompt
                            ? prompt.prompt()
                            : action instanceof AgentAction.DirectUserPromptAction direct
                                    ? direct.prompt()
                                    : null;
            boolean continuation =
                    runtime.control.waiting(Wait.BREAKER)
                            && promptText != null
                            && "continue".equalsIgnoreCase(promptText.strip());
            RequestHandle handle =
                    continuation && previous != null
                            ? new RequestHandle(runtime, previous.episode)
                            : new RequestHandle(runtime, runtime.continuations().newEpisode());
            if (callback != null) handle.result.thenAccept(callback);
            runtime.actionQueue.add(new QueuedRequest(action, handle));
            notifyExecutionChanged();
            return handle;
        }
    }

    void enqueue(@NonNull AgentAction action) {
        if (action instanceof AgentAction.TerminateAction && !runtime.control.open()) return;
        startTask(null, action);
    }

    void bind(@NonNull LlmBinding binding) {
        runtime.baseBinding = binding;
    }

    @NonNull LlmBinding binding() {
        return runtime.binding;
    }

    @NonNull AgentState state() {
        return runtime.control.state();
    }

    @NonNull ReadHistory readHistory() {
        return runtime.readHistory;
    }

    @NonNull Set<String> whitelistedToolsView() {
        return runtime.whitelistedTools;
    }

    void setExecutionPolicy(@NonNull AgentExecutionPolicy policy) {
        var terminal = policy.terminal();
        if (terminal != null && !runtime.whitelistedTools.contains(terminal.tool()))
            throw new IllegalArgumentException("Terminal tool must be in the agent's whitelist");
        runtime.executionPolicy = policy;
    }

    @NonNull String agentId() {
        return runtime.agentId;
    }

    void setLocale(Locale locale) {
        runtime.locale = locale != null ? locale : Locale.ENGLISH;
        runtime.toolBoundary.locale(runtime.locale);
    }

    @NonNull Locale locale() {
        return runtime.locale;
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
        if (runtime.persona.equals(persona)) return;
        runtime.persona = persona;
        runtime.configurationRevision++;
        runtime.whitelistedTools =
                persona.whitelistedTools().stream()
                        .map(ToolDefinition::name)
                        .collect(Collectors.toUnmodifiableSet());
        notifyExecutionChanged();
    }

    void refreshConfiguration() {
        var selection = runtime.sessionPlugins;
        String owner = runtime.owner;
        if (selection == null || owner == null) {
            runtime.binding = runtime.baseBinding;
            return;
        }
        var available =
                selection.tools(
                        runtime.sessionId.toString(),
                        Set.copyOf(runtime.toolEngine.getActiveTools(null)));
        var original = runtime.basePersona;
        var base =
                new AgentProfile(
                        original.name(),
                        original.description(),
                        original.role().name(),
                        original.whitelistedTools().stream()
                                .map(ToolDefinition::name)
                                .collect(Collectors.toSet()),
                        null,
                        null,
                        Map.of());
        var intent =
                selection.configure(
                        owner,
                        runtime.sessionId.toString(),
                        runtime.agentId,
                        original.configurationOwner(),
                        base,
                        available.stream()
                                .map(tool -> AgentProfiles.configurationTool(tool))
                                .toList(),
                        runtime.currentTask());
        if (intent == null) {
            applyPersona(original);
            runtime.binding = runtime.baseBinding;
            runtime.prompt = null;
            return;
        }
        var profile = intent.profile();
        var tiers = runtime.modelTierRegistry;
        if (tiers == null) throw new IllegalStateException("Model tiers unavailable");
        var resolved =
                AgentProfiles.resolve(
                        runtime.agentId, owner, profile, available, runtime.baseBinding, tiers);
        var transition = intent == null ? null : intent.transition();
        boolean changing =
                transition != null && !transition.key().equals(runtime.configurationTransition);
        String summary = changing ? summarizeForRoleChange() : "";
        var next = resolved.persona();
        applyPersona(
                new AgentPersona(
                        next.id(),
                        next.name(),
                        next.description(),
                        next.whitelistedTools(),
                        next.role(),
                        original.configurationOwner()));
        if (!runtime.binding.equals(resolved.binding())
                || !Objects.equals(runtime.prompt, resolved.prompt()))
            runtime.configurationRevision++;
        runtime.binding = resolved.binding();
        runtime.prompt = resolved.prompt();
        if (changing) {
            var value = Nullness.requireNonNull(transition);
            restartAfterConfiguration(summary, value.prompt(), JsonValues.toMap(value.data()));
            runtime.configurationTransition = value.key();
        }
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

    void restartAfterConfiguration(
            @NonNull String summary,
            @NonNull String prompt,
            @NonNull Map<String, @Nullable Object> data) {
        if (!summary.isBlank() && !"{}".equals(summary)) {
            runtime.output().appendTurn(TurnRecord.rewind(runtime.output().nextTurn(), 0));
            runtime.models().appendAgentInit(runtime.models().linkCurrentSystemMessage());
            runtime.output()
                    .appendTurn(TurnRecord.compactionSummary(runtime.output().nextTurn(), summary));
        } else {
            runtime.models().rewindAndRestoreHistory();
        }
        runtime.output()
                .appendTurn(
                        PromptCompiler.sourcedUserPrompt(
                                runtime.output().nextTurn(), prompt, data));
    }

    void onTermination(@NonNull Runnable callback) {
        synchronized (runtime) {
            if (runtime.terminationNotified) {
                callback.run();
                return;
            }
            var previous = runtime.terminationCallback;
            runtime.terminationCallback =
                    previous == null
                            ? callback
                            : () -> {
                                try {
                                    previous.run();
                                } finally {
                                    callback.run();
                                }
                            };
        }
    }

    @NonNull UUID sessionId() {
        return runtime.sessionId;
    }

    void notifyTermination() {
        Runnable callback;
        synchronized (runtime) {
            if (runtime.terminationNotified) return;
            runtime.terminationNotified = true;
            callback = runtime.terminationCallback;
            runtime.terminationCallback = null;
        }
        try {
            var events = runtime.lifecycleEvents;
            String currentOwner = runtime.owner;
            var control = runtime.control;
            if (control instanceof ExecutionControl.Closed closed
                    && closed.reason() != ExecutionControl.CloseReason.SHUTDOWN
                    && events != null
                    && currentOwner != null)
                events.agentTerminated(currentOwner, runtime.sessionId.toString(), runtime.agentId);
        } finally {
            if (callback != null) callback.run();
        }
    }

    void terminate() {
        close(ExecutionControl.CloseReason.AGENT_DELETED);
    }

    void close(ExecutionControl.@NonNull CloseReason reason) {
        synchronized (runtime) {
            if (!runtime.control.open()) return;
            AgentResult interrupted =
                    AgentResult.failure(
                            Msg.get(runtime.locale, "error.agent.interrupted"), Map.of());
            RequestHandle active = runtime.control.request();
            if (active != null) {
                active.cancelled = true;
                active.releaseWaits();
                active.result.complete(interrupted);
                if (runtime.control.waiting(Wait.PLUGIN)) active.settled.complete(true);
            }
            runtime.control = new ExecutionControl.Closed(reason);
            List<QueuedRequest> queued = new ArrayList<>(runtime.deferredUserPrompts);
            runtime.deferredUserPrompts.clear();
            runtime.actionQueue.drainTo(queued);
            for (QueuedRequest request : queued) {
                request.handle().result.complete(interrupted);
                request.handle().settled.complete(true);
            }
        }
        notifyExecutionChanged();
        runtime.toolBoundary.clear();
        Thread thread = runtime.runningThread;
        if (thread != null && thread != Thread.currentThread()) thread.interrupt();
    }

    void attachLifecycleEvents(@NonNull PluginLifecycleEvents events) {
        runtime.lifecycleEvents = events;
    }
}
