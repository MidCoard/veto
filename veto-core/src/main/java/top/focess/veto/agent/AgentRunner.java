package top.focess.veto.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.AgentRuntimeState.BreakerTripException;
import top.focess.veto.agent.ExecutionControl.Wait;
import top.focess.veto.agent.continuation.RequestContinuationStore;
import top.focess.veto.agent.drift.ReadHistory;
import top.focess.veto.agent.identity.AgentPersona;
import top.focess.veto.agent.intercept.Gateway;
import top.focess.veto.agent.intercept.HitlRegistry;
import top.focess.veto.agent.intercept.IngressDefense;
import top.focess.veto.agent.intercept.LoopInterceptor;
import top.focess.veto.agent.intercept.VetoPrompt;
import top.focess.veto.agent.loop.PromptCompiler;
import top.focess.veto.agent.tool.ToolCallContextHolder;
import top.focess.veto.agent.tool.ToolEngine;
import top.focess.veto.api.agent.AgentAction;
import top.focess.veto.api.agent.AgentResult;
import top.focess.veto.api.agent.AgentState;
import top.focess.veto.api.agent.ToolCallEvent;
import top.focess.veto.api.agent.ToolResultEvent;
import top.focess.veto.api.agent.tool.ToolResult;
import top.focess.veto.api.agent.workflow.ActionContext;
import top.focess.veto.api.agent.workflow.PluginWork;
import top.focess.veto.api.llm.LlmBinding;
import top.focess.veto.api.llm.ResponseContract;
import top.focess.veto.api.llm.ToolCall;
import top.focess.veto.api.llm.ToolResultPresentationMode;
import top.focess.veto.api.llm.VetoResponse;
import top.focess.veto.api.plugin.contract.AgentWorkSource;
import top.focess.veto.bus.DeltaBroker;
import top.focess.veto.i18n.Msg;
import top.focess.veto.integration.plugins.PluginLifecycleEvents;
import top.focess.veto.integration.plugins.SessionPlugins;
import top.focess.veto.llm.core.UniformLLMCaller;
import top.focess.veto.memory.TurnLogService;
import top.focess.veto.model.tier.ModelTierRegistry;
import top.focess.veto.util.Nullness;
import top.focess.veto.vault.KeysteadVault;
import top.focess.veto.vault.UserContext;

/** Public agent facade and single-thread action-queue coordinator. */
public final class AgentRunner implements Runnable {
    private final @NonNull AgentRuntimeState runtime;

    public AgentRunner(
            @NonNull String agentId,
            @NonNull AgentPersona persona,
            @NonNull ToolEngine toolEngine,
            @NonNull Gateway gateway,
            @NonNull HitlRegistry hitlRegistry,
            @NonNull IngressDefense ingressDefense,
            List<LoopInterceptor> interceptors,
            @NonNull PromptCompiler promptCompiler,
            @NonNull UniformLLMCaller caller,
            @NonNull ObjectMapper objectMapper,
            long maxCallsPerEpisode,
            @NonNull LlmBinding binding,
            DeltaBroker deltaBroker,
            @NonNull UUID userId,
            TurnLogService turnLogService) {
        this(
                agentId,
                persona,
                toolEngine,
                gateway,
                hitlRegistry,
                ingressDefense,
                interceptors,
                promptCompiler,
                caller,
                objectMapper,
                maxCallsPerEpisode,
                binding,
                deltaBroker,
                userId,
                turnLogService,
                null,
                UUID.fromString(agentId));
    }

    public AgentRunner(
            @NonNull String agentId,
            @NonNull AgentPersona persona,
            @NonNull ToolEngine toolEngine,
            @NonNull Gateway gateway,
            @NonNull HitlRegistry hitlRegistry,
            @NonNull IngressDefense ingressDefense,
            List<LoopInterceptor> interceptors,
            @NonNull PromptCompiler promptCompiler,
            @NonNull UniformLLMCaller caller,
            @NonNull ObjectMapper objectMapper,
            long maxCallsPerEpisode,
            @NonNull LlmBinding binding,
            DeltaBroker deltaBroker,
            @NonNull UUID userId,
            TurnLogService turnLogService,
            String owner,
            @NonNull UUID sessionId) {
        runtime =
                new AgentRuntimeState(
                        agentId,
                        persona,
                        toolEngine,
                        gateway,
                        hitlRegistry,
                        ingressDefense,
                        interceptors,
                        promptCompiler,
                        caller,
                        objectMapper,
                        maxCallsPerEpisode,
                        binding,
                        deltaBroker,
                        userId,
                        turnLogService,
                        owner,
                        sessionId);
        runtime.initializeComponents();
    }

    public void attachExecutionVault(@NonNull KeysteadVault vault) {
        runtime.continuations().attachExecutionVault(vault);
    }

    public String executionWaitReason() {
        return runtime.lifecycle().executionWaitReason();
    }

    void configureModelTiers(ModelTierRegistry registry) {
        runtime.lifecycle().configureModelTiers(registry);
    }

    public boolean cancelTask(
            @NonNull CompletableFuture<AgentResult> result, @NonNull Duration timeout)
            throws InterruptedException {
        return runtime.lifecycle().cancelTask(result, timeout);
    }

    public @NonNull PluginContextSnapshot pluginContext() {
        return runtime.lifecycle().pluginContext();
    }

    public void setToolResultPresentation(
            @NonNull ToolResultPresentationMode toolResultPresentation) {
        runtime.lifecycle().setToolResultPresentation(toolResultPresentation);
    }

    public void attachContinuationStore(@NonNull RequestContinuationStore store) {
        runtime.continuations().attachContinuationStore(store);
    }

    public void attachWorkSource(@NonNull AgentWorkSource service) {
        runtime.continuations().attachWorkSource(service);
    }

    void onBackgroundRequest(@NonNull Consumer<RequestHandle> listener) {
        runtime.backgroundRequestListener = listener;
    }

    public void signalWork() {
        runtime.continuations().signalWork();
    }

    public void run() {
        runtime.runningThread = Thread.currentThread();
        // Stamp the session owner onto the agent's virtual thread so credential resolution on the
        // LLM-call path (CredentialResolver → KeysteadVault.currentHandle → UserContext.get) and
        // the embedder path resolve against the owner's vault rather than the single-active-handle
        // fallback. Owner is set once by AgentService before this thread starts, so a single set at
        // entry covers every turn; clear on exit so the thread never leaks a stale user.
        String currentOwner = runtime.owner;
        if (currentOwner != null) {
            UserContext.set(currentOwner);
        }
        try {
            while (runtime.control.open() && runtime.control.state() != AgentState.TERMINATED) {
                try {
                    QueuedRequest queued = runtime.actionQueue.take();
                    AgentAction action = queued.action();
                    if (queued.handle().cancelled) {
                        queued.handle().settled.complete(true);
                        continue;
                    }
                    if (action instanceof AgentAction.WorkAvailableAction) {
                        runtime.workQueued.set(false);
                        queued.handle().result.complete(AgentResult.success("", Map.of()));
                        queued.handle().settled.complete(true);
                        QueuedRequest work = runtime.continuations().claimWork();
                        if (work == null) continue;
                        queued = work;
                        action = work.action();
                    }
                    if (action instanceof AgentAction.TerminateAction) {
                        runtime.lifecycle().terminate();
                        queued.handle().result.complete(AgentResult.success("", Map.of()));
                        queued.handle().settled.complete(true);
                        break;
                    }
                    if (action instanceof AgentAction.ConfigurationAction) {
                        try {
                            runtime.lifecycle().refreshConfiguration();
                            queued.handle().result.complete(AgentResult.success("", Map.of()));
                        } catch (RuntimeException error) {
                            queued.handle()
                                    .result
                                    .complete(
                                            AgentResult.failure(
                                                    runtime.lifecycle().failureMessage(error),
                                                    Map.of()));
                            AgentRuntimeState.log.warn(
                                    "Agent configuration was not applied", error);
                        } finally {
                            queued.handle().settled.complete(true);
                        }
                        continue;
                    }
                    if (runtime.control.waiting(Wait.PLUGIN)
                            && !(action instanceof AgentAction.WorkAction)) {
                        runtime.deferredUserPrompts.add(queued);
                        runtime.continuations().signalWork();
                        continue;
                    }
                    runtime.control = runtime.control.withRequest(queued.handle());
                    if (action instanceof AgentAction.CompactAction) {
                        runtime.lifecycle().transitionTo(AgentState.RUNNING);
                        try {
                            runtime.lifecycle().checkExecutionBoundary();
                            runtime.lifecycle().processCompaction();
                            runtime.lifecycle().completeSuccess();
                        } catch (Exception e) {
                            AgentRuntimeState.log.error(
                                    "Agent {} compaction failed", runtime.agentId, e);
                            runtime.lifecycle()
                                    .completeFailure(runtime.lifecycle().failureMessage(e));
                        } finally {
                            // Clear a stale interrupt flag (see the UserPromptAction finally).
                            if (Thread.interrupted()) {
                                AgentRuntimeState.log.debug(
                                        "Agent {} cleared a stale interrupt after compaction",
                                        runtime.agentId);
                            }
                            queued.handle().settled.complete(true);
                            runtime.control = runtime.control.withRequest(null);
                            if (runtime.control.open() && !runtime.control.waiting(Wait.PLUGIN))
                                runtime.lifecycle().transitionTo(AgentState.IDLE);
                        }
                        continue;
                    }
                    if (action instanceof AgentAction.UserPromptAction
                            || action instanceof AgentAction.DirectUserPromptAction
                            || action instanceof AgentAction.WorkAction) {
                        String prompt =
                                action instanceof AgentAction.UserPromptAction upa
                                        ? upa.prompt()
                                        : action
                                                        instanceof
                                                        AgentAction.DirectUserPromptAction direct
                                                ? direct.prompt()
                                                : "";
                        RequestHandle taskCancellation = queued.handle();
                        runtime.lifecycle().transitionTo(AgentState.RUNNING);
                        try {
                            runtime.lifecycle().checkTaskCancellation();
                            runtime.lifecycle().clearWait(Wait.PLUGIN);
                            runtime.lifecycle().checkExecutionBoundary();
                            runtime.lifecycle().refreshConfiguration();
                            if (action instanceof AgentAction.WorkAction) {
                                // Consume readiness before admitting any new model/tool effects.
                                runtime.lifecycle().currentRequest().awaiting();
                                runtime.models().refreshSystemHistory();

                                if (runtime.continuations().injectObservations())
                                    runAutonomous(null);
                            } else {
                                var firstPrompt = runtime.lifecycle().processUserPrompt(prompt);
                                runAutonomous(firstPrompt);
                            }
                            runtime.lifecycle().checkTaskCancellation();
                            runtime.continuations().completeOrWaitForWork();
                        } catch (LinkageError error) {
                            runtime.lifecycle()
                                    .completeFailure(runtime.lifecycle().failureMessage(error));
                            throw error;
                        } catch (BreakerTripException e) {
                            runtime.lifecycle().completeBreaker();
                        } catch (Exception e) {
                            if (taskCancellation != null && taskCancellation.cancelled) {
                                synchronized (runtime) {
                                    // Wait for cancelTask to finish sending the one interrupt.
                                    AgentLifecycle.clearTaskInterrupt();
                                }
                                runtime.lifecycle()
                                        .completeFailure(
                                                Msg.get(
                                                        runtime.locale,
                                                        "error.agent.taskCancelled"),
                                                true,
                                                taskCancellation.requestId);
                            } else {
                                AgentRuntimeState.log.error(
                                        "Agent {} task failed", runtime.agentId, e);
                                runtime.lifecycle()
                                        .completeFailure(runtime.lifecycle().failureMessage(e));
                            }
                        } finally {
                            // A stray mid-round interrupt (external interference tripping the LLM
                            // HTTP call, a DB socket dying on interrupt) leaves the thread's
                            // interrupt flag SET. If it survives to the next actionQueue.take()
                            // the park throws instantly and the loop breaks - the agent looks
                            // crashed though nothing cancelled it. The round is already aborted
                            // (the cancel intent, if any, is honored), so clear the stale flag; a
                            // genuine cancel while PARKED still interrupts take() and breaks.
                            if (Thread.interrupted()) {
                                AgentRuntimeState.log.debug(
                                        "Agent {} cleared a stale interrupt after a prompt",
                                        runtime.agentId);
                            }
                            if (runtime.control.open() && !runtime.control.waiting(Wait.PLUGIN))
                                runtime.lifecycle().transitionTo(AgentState.IDLE);
                            synchronized (runtime) {
                                if (!runtime.control.waiting(Wait.PLUGIN)
                                        && !runtime.control.waiting(Wait.BREAKER)) {
                                    runtime.control = runtime.control.withRequest(null);
                                }
                                taskCancellation.settled.complete(true);
                                AgentLifecycle.clearTaskInterrupt();
                            }
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } catch (LinkageError error) {
            // A broken runtime cannot accept another episode. Release the current caller with a
            // failure before lifecycle cleanup, rather than leaving the UI waiting indefinitely.
            AgentRuntimeState.log.error("Agent {} runtime linkage failed", runtime.agentId, error);
            runtime.lifecycle().completeFailure(runtime.lifecycle().failureMessage(error));
        } finally {
            runtime.runningThread = null;
            try {
                runtime.lifecycle().terminate();
            } finally {
                try {
                    UserContext.clear();
                } finally {
                    runtime.lifecycle().notifyTermination();
                }
            }
        }
    }

    void runAutonomous(ModelSession.Prepared firstPrompt) {
        ToolCallContextHolder.ResponseDirective pending = null;
        String originatingCall = null;
        ModelExchange.Result previousExchange = null;
        while (runtime.control.state() == AgentState.RUNNING) {
            runtime.lifecycle().checkTaskCancellation();
            // Mid-episode task lifecycle: a background task that ended (or that the user
            // stopped) during THIS episode is reported at the next iteration, not only at the
            // start of the next episode. Cheap no-op when the queue is empty.
            // processUserPrompt already drained notices before preparing the first immutable
            // request. Do not mutate history between that compilation and its dispatch.
            if (firstPrompt == null) {
                runtime.continuations().injectObservations();
            }
            if (pending == null
                    && runtime.lifecycle().currentRequest().episode.breaker().shouldTrip()) {
                runtime.lifecycle().tripBreaker();
                throw new BreakerTripException();
            }
            var accepted = pending;
            if (accepted instanceof ToolCallContextHolder.ResponseDirective.Execute execution) {
                pending = null;
                runtime.lifecycle().checkTaskCancellation();
                long configuration = runtime.configurationRevision;
                var sources = new RequestEvidence.WorkSources();
                try {
                    execution.work().run(workRuntime(originatingCall, sources));
                } finally {
                    sources.close();
                }
                if (runtime.configurationRevision != configuration) continue;
                return;
            }
            ModelExchange.Result exchange;
            if (accepted instanceof ToolCallContextHolder.ResponseDirective.Finish answer) {
                pending = null;
                if (!answer.publish()) {
                    String result = answer.response().message();
                    runtime.lifecycle().currentRequest().message = result == null ? "" : result;
                    return;
                }
                exchange =
                        new ModelExchange.Result(
                                Nullness.requireNonNull(previousExchange).request(),
                                answer.response(),
                                answer.citations(),
                                originatingCall,
                                true);
            } else {
                exchange = runtime.models().callModel(firstPrompt);
                firstPrompt = null;
            }
            previousExchange = exchange;
            VetoResponse response = exchange.response();
            originatingCall = exchange.modelCallId();
            runtime.lifecycle().checkTaskCancellation();

            runtime.output().appendThought(response, exchange.modelCallId());
            String message = response.message();
            if (message != null && !message.isBlank()) {
                var calls = response.calls();
                runtime.output()
                        .emitMessage(
                                message,
                                RequestEvidence.forRequest(
                                        exchange.citations(),
                                        runtime.output().requestIdentity(),
                                        exchange.modelCallId(),
                                        message),
                                exchange.modelCallId(),
                                false,
                                calls != null
                                        && calls.stream()
                                                .anyMatch(call -> call.nativeState() != null));
            }
            List<ToolCall> responseCalls = response.calls();
            if (responseCalls != null && !responseCalls.isEmpty()) {
                pending =
                        runtime.tools()
                                .executeToolCalls(
                                        responseCalls,
                                        response.thought(),
                                        new ToolBatch(exchange, false, null));
            } else {
                // No tool calls: the agent has emitted its answer with nothing further to act
                // on. Termination routes on call presence - calls absent means stop. The agent
                // reasons within its model invocation. Stop the episode here; the emitted
                // message is the final answer.
                return;
            }
        }
    }

    PluginWork.@NonNull Runtime workRuntime(
            String sourceCallId, RequestEvidence.@NonNull WorkSources sources) {
        long configuration = runtime.configurationRevision;
        return new PluginWork.Runtime() {
            @Override
            public void beforeStep() {
                sources.check();
                runtime.lifecycle().checkTaskCancellation();
                runtime.continuations().injectObservations();
            }

            @Override
            public boolean running() {
                return sources.active()
                        && runtime.control.state() == AgentState.RUNNING
                        && runtime.configurationRevision == configuration;
            }

            @Override
            public @NonNull ToolResult tool(
                    @NonNull ToolCall call, @NonNull ActionContext context) {
                sources.check();
                return runtime.tools().executeOneCall(call, new ToolBatch(null, false, context));
            }

            @Override
            public PluginWork.@NonNull Generated generate(
                    PluginWork.@NonNull ModelInput action, @NonNull ResponseContract contract) {
                sources.check();
                ModelExchange.Result exchange = callGenerate(action, contract);
                sources.register(exchange.citations());
                return new PluginWork.Generated(
                        exchange.response(), exchange.citations(), exchange.modelCallId());
            }

            @Override
            public void message(
                    @NonNull String text,
                    PluginWork.Source citations,
                    String callId,
                    boolean forwarded) {
                runtime.output()
                        .emitMessage(
                                text,
                                sources.consume(
                                        citations,
                                        runtime.output().requestIdentity(),
                                        callId,
                                        text),
                                callId,
                                forwarded);
            }

            @Override
            public @NonNull String prompt(
                    @NonNull String source, @NonNull Map<String, Object> data) {
                return PromptCompiler.compileText(source, data);
            }

            @Override
            public void observation(@NonNull String topic, @NonNull String reason) {
                sources.check();
                runtime.output().appendObservation(topic, reason);
            }

            public String sourceCallId() {
                return sourceCallId;
            }
        };
    }

    ModelExchange.@NonNull Result callGenerate(
            PluginWork.@NonNull ModelInput gen, @NonNull ResponseContract contract) {
        if (runtime.lifecycle().currentRequest().episode.breaker().shouldTrip()) {
            runtime.lifecycle().tripBreaker();
            throw new BreakerTripException();
        }
        while (true) {
            ModelExchange.Result exchange = runtime.models().callModel(null, gen, contract);
            VetoResponse response = exchange.response();
            if (!Boolean.FALSE.equals(gen.thought()))
                runtime.output().appendThought(response, exchange.modelCallId());
            var calls = response.calls();
            if (calls == null || calls.isEmpty()) return exchange;
            var accepted =
                    runtime.tools()
                            .executeToolCalls(
                                    calls, response.thought(), new ToolBatch(exchange, true, null));
            if (accepted instanceof ToolCallContextHolder.ResponseDirective.Finish answer)
                return new ModelExchange.Result(
                        exchange.request(),
                        answer.response(),
                        answer.citations(),
                        exchange.modelCallId(),
                        true);
            runtime.lifecycle().checkTaskCancellation();
        }
    }

    public void seedHistory(@NonNull List<TurnRecord> replayed) {
        if (runtime.output().seedHistory(replayed))
            runtime.lifecycle().saveExecutionWait(Wait.INTERRUPTED);
    }

    public boolean hasPendingWork() {
        return runtime.lifecycle().hasPendingWork();
    }

    public @NonNull RequestHandle startTask(
            Consumer<AgentResult> callback, @NonNull AgentAction action) {
        return runtime.lifecycle().startTask(callback, action);
    }

    public void enqueue(@NonNull AgentAction action) {
        runtime.lifecycle().enqueue(action);
    }

    public void bind(@NonNull LlmBinding binding) {
        runtime.lifecycle().bind(binding);
    }

    public @NonNull LlmBinding binding() {
        return runtime.lifecycle().binding();
    }

    public void addMessageListener(@NonNull Consumer<String> listener) {
        runtime.output().addMessageListener(listener);
    }

    public void removeMessageListener(@NonNull Consumer<String> listener) {
        runtime.output().removeMessageListener(listener);
    }

    public void addThoughtListener(@NonNull Consumer<String> listener) {
        runtime.output().addThoughtListener(listener);
    }

    public void removeThoughtListener(@NonNull Consumer<String> listener) {
        runtime.output().removeThoughtListener(listener);
    }

    public void addVetoListener(@NonNull Consumer<VetoPrompt> listener) {
        runtime.output().addVetoListener(listener);
    }

    public void removeVetoListener(@NonNull Consumer<VetoPrompt> listener) {
        runtime.output().removeVetoListener(listener);
    }

    public void addToolCallListener(@NonNull Consumer<ToolCallEvent> listener) {
        runtime.output().addToolCallListener(listener);
    }

    public void removeToolCallListener(@NonNull Consumer<ToolCallEvent> listener) {
        runtime.output().removeToolCallListener(listener);
    }

    public void addToolResultListener(@NonNull Consumer<ToolResultEvent> listener) {
        runtime.output().addToolResultListener(listener);
    }

    public void removeToolResultListener(@NonNull Consumer<ToolResultEvent> listener) {
        runtime.output().removeToolResultListener(listener);
    }

    public @NonNull AgentState state() {
        return runtime.lifecycle().state();
    }

    public @NonNull List<TurnRecord> history() {
        return runtime.output().history();
    }

    public @NonNull ReadHistory readHistory() {
        return runtime.lifecycle().readHistory();
    }

    public @NonNull Set<String> whitelistedToolsView() {
        return runtime.lifecycle().whitelistedToolsView();
    }

    public void setExecutionPolicy(@NonNull AgentExecutionPolicy policy) {
        runtime.lifecycle().setExecutionPolicy(policy);
    }

    public boolean budgetExhausted() {
        RequestHandle request = runtime.control.request();
        return request != null && request.episode.breaker().shouldTrip();
    }

    public @NonNull String agentId() {
        return runtime.lifecycle().agentId();
    }

    public void refreshConfiguration() {
        runtime.lifecycle().enqueue(new AgentAction.ConfigurationAction());
    }

    public void setLocale(Locale locale) {
        runtime.lifecycle().setLocale(locale);
    }

    public @NonNull Locale locale() {
        return runtime.lifecycle().locale();
    }

    public @NonNull AgentPersona personaView() {
        return runtime.lifecycle().personaView();
    }

    public void attachSessionPlugins(@NonNull SessionPlugins value) {
        runtime.lifecycle().attachSessionPlugins(value);
    }

    public void applyPersona(@NonNull AgentPersona persona) {
        runtime.lifecycle().applyPersona(persona);
    }

    void onTermination(@NonNull Runnable callback) {
        runtime.lifecycle().onTermination(callback);
    }

    public @NonNull UUID sessionId() {
        return runtime.lifecycle().sessionId();
    }

    public void shutdown() {
        runtime.lifecycle().close(ExecutionControl.CloseReason.SHUTDOWN);
    }

    public void terminate() {
        runtime.lifecycle().terminate();
    }

    public void attachLifecycleEvents(@NonNull PluginLifecycleEvents events) {
        runtime.lifecycle().attachLifecycleEvents(events);
    }
}
