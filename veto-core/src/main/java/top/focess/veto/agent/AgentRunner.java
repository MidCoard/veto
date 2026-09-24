package top.focess.veto.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.AgentRuntimeState.BreakerTripException;
import top.focess.veto.agent.AgentRuntimeState.TaskCancellation;
import top.focess.veto.agent.drift.ReadHistory;
import top.focess.veto.agent.identity.AgentPersona;
import top.focess.veto.agent.intercept.Gateway;
import top.focess.veto.agent.intercept.HitlRegistry;
import top.focess.veto.agent.intercept.IngressDefense;
import top.focess.veto.agent.intercept.LoopInterceptor;
import top.focess.veto.agent.intercept.VetoPrompt;
import top.focess.veto.agent.loop.PromptCompiler;
import top.focess.veto.agent.tool.ToolDefinition;
import top.focess.veto.agent.tool.ToolEngine;
import top.focess.veto.api.agent.AgentAction;
import top.focess.veto.api.agent.AgentResult;
import top.focess.veto.api.agent.AgentState;
import top.focess.veto.api.agent.ToolCallEvent;
import top.focess.veto.api.agent.ToolResultEvent;
import top.focess.veto.api.llm.LlmBinding;
import top.focess.veto.api.llm.ToolResultPresentationMode;
import top.focess.veto.bus.DeltaBroker;
import top.focess.veto.i18n.Msg;
import top.focess.veto.integration.plugins.PluginLifecycleEvents;
import top.focess.veto.integration.plugins.SessionPlugins;
import top.focess.veto.llm.core.UniformLLMCaller;
import top.focess.veto.memory.TurnLogService;
import top.focess.veto.model.tier.ModelTierRegistry;
import top.focess.veto.monitor.MonitorService;
import top.focess.veto.monitor.RequestContinuationStore;
import top.focess.veto.sandbox.BackgroundTaskManager;
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
            TurnLogService turnLogService,
            BackgroundTaskManager backgroundTaskManager) {
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
                        backgroundTaskManager);
        runtime.initializeComponents();
    }

    public void attachMonitorVault(@NonNull KeysteadVault vault) {
        runtime.monitor().attachMonitorVault(vault);
    }

    public String executionWaitReason() {
        return runtime.lifecycle().executionWaitReason();
    }

    void configurePlan(ModelTierRegistry registry, int maxSteps) {
        runtime.lifecycle().configurePlan(registry, maxSteps);
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
        runtime.monitor().attachContinuationStore(store);
    }

    public void attachMonitor(@NonNull MonitorService service) {
        runtime.monitor().attachMonitor(service);
    }

    public void signalMonitor() {
        runtime.monitor().signalMonitor();
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
            while (runtime.sessionAlive && runtime.state != AgentState.TERMINATED) {
                try {
                    AgentAction action = runtime.actionQueue.take();
                    if (action instanceof AgentAction.MonitorAction) {
                        runtime.monitorQueued.set(false);
                        runtime.monitor().processMonitor();
                        continue;
                    }
                    if (action instanceof AgentAction.TerminateAction) {
                        runtime.lifecycle().transitionTo(AgentState.TERMINATED);
                        break;
                    }
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
                            if (runtime.sessionAlive && !runtime.waitingForMonitor)
                                runtime.lifecycle().transitionTo(AgentState.IDLE);
                        }
                        continue;
                    }
                    if (action instanceof AgentAction.UserPromptAction
                            || action instanceof AgentAction.DirectUserPromptAction) {
                        if (runtime.waitingForMonitor
                                && action instanceof AgentAction.DirectUserPromptAction direct) {
                            runtime.deferredUserPrompts.add(direct);
                            // A prior monitor wake may have yielded to this queued prompt.
                            runtime.monitor().signalMonitor();
                            continue;
                        }
                        runtime.handlingDirectUserPrompt =
                                action instanceof AgentAction.DirectUserPromptAction;
                        String prompt =
                                action instanceof AgentAction.UserPromptAction upa
                                        ? upa.prompt()
                                        : ((AgentAction.DirectUserPromptAction) action).prompt();
                        TaskCancellation taskCancellation;
                        synchronized (runtime) {
                            taskCancellation = runtime.taskActions.remove(action);
                            runtime.activeCancellation = taskCancellation;
                        }
                        runtime.lifecycle().transitionTo(AgentState.RUNNING);
                        try {
                            runtime.lifecycle().checkTaskCancellation();
                            runtime.waitingForMonitor = false;
                            runtime.lifecycle().checkExecutionBoundary();
                            runtime.lifecycle().processUserPrompt(prompt);
                            runtime.lifecycle().checkTaskCancellation();
                            runtime.monitor().completeOrWaitForMonitor();
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
                            if (!runtime.waitingForMonitor)
                                runtime.handlingDirectUserPrompt = false;
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
                            if (runtime.sessionAlive && !runtime.waitingForMonitor)
                                runtime.lifecycle().transitionTo(AgentState.IDLE);
                            synchronized (runtime) {
                                runtime.activeCancellation = null;
                                AgentLifecycle.clearTaskInterrupt();
                                if (taskCancellation != null) {
                                    runtime.cancellableTasks.remove(taskCancellation.result);
                                    runtime.lastExitedTask = taskCancellation.result;
                                    taskCancellation.exited.complete(true);
                                }
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
                UserContext.clear();
            }
        }
    }

    public void setRecoveredTasks(@NonNull List<RecoveredTask> tasks) {
        runtime.output().setRecoveredTasks(tasks);
    }

    public void seedHistory(@NonNull List<TurnRecord> replayed) {
        runtime.output().seedHistory(replayed);
    }

    public boolean hasPendingWork() {
        return runtime.lifecycle().hasPendingWork();
    }

    public void startTask(Consumer<AgentResult> callback, @NonNull AgentAction action) {
        runtime.lifecycle().startTask(callback, action);
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

    public @NonNull AgentResult await(@NonNull Duration timeout)
            throws TimeoutException, InterruptedException {
        return runtime.lifecycle().await(timeout);
    }

    public @NonNull CompletableFuture<AgentResult> result() {
        return runtime.lifecycle().result();
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

    public void setCompletionTool(@NonNull String toolName) {
        runtime.lifecycle().setCompletionTool(toolName);
    }

    public @NonNull String agentId() {
        return runtime.lifecycle().agentId();
    }

    public UUID groupId() {
        return runtime.lifecycle().groupId();
    }

    public void setGroupId(UUID groupId) {
        runtime.lifecycle().setGroupId(groupId);
    }

    public void restoreLeader(
            @NonNull UUID restoredGroup,
            @NonNull LlmBinding leaderBinding,
            @NonNull Set<ToolDefinition> tools) {
        runtime.lifecycle().restoreLeader(restoredGroup, leaderBinding, tools);
    }

    public void setOwner(String owner) {
        runtime.lifecycle().setOwner(owner);
    }

    public void setLocale(Locale locale) {
        runtime.lifecycle().setLocale(locale);
    }

    public @NonNull Locale locale() {
        return runtime.lifecycle().locale();
    }

    public void setSessionId(@NonNull UUID sessionId) {
        runtime.lifecycle().setSessionId(sessionId);
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

    public void terminate() {
        runtime.lifecycle().terminate();
    }

    public void attachLifecycleEvents(@NonNull PluginLifecycleEvents events) {
        runtime.lifecycle().attachLifecycleEvents(events);
    }
}
