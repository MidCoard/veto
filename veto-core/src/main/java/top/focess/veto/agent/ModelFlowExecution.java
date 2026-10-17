package top.focess.veto.agent;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.loop.MessageCitations;
import top.focess.veto.agent.loop.PromptCompiler;
import top.focess.veto.api.agent.AgentState;
import top.focess.veto.api.agent.control.SourceEvidence;
import top.focess.veto.api.agent.tool.ToolResult;
import top.focess.veto.api.agent.workflow.ActionContext;
import top.focess.veto.api.agent.workflow.ModelFlow;
import top.focess.veto.api.llm.ResponseContract;
import top.focess.veto.api.llm.ToolCall;

/** Runner-thread-confined runtime for one flow invocation; receipts expire when it returns. */
final class ModelFlowExecution implements ModelFlow.Runtime {
    private final @NonNull ModelFlowStack stack;
    private final @NonNull ModelSession models;
    private final @NonNull AgentToolExecution tools;
    private final @NonNull ModelFlow flow;
    private final String callId;
    private final long revision;
    final @NonNull GeneratedSourceReceipts sources = new GeneratedSourceReceipts();
    boolean finished;

    ModelFlowExecution(
            @NonNull ModelFlowStack stack,
            @NonNull ModelSession models,
            @NonNull AgentToolExecution tools,
            @NonNull ModelFlow flow,
            String callId) {
        this.stack = stack;
        this.models = models;
        this.tools = tools;
        this.flow = flow;
        this.callId = callId;
        this.revision = models.runner.configurationRevision();
    }

    @Override
    public @NonNull String input() {
        sources.check();
        return models.runner.currentRequest().episode.task();
    }

    @Override
    public void push(@NonNull ModelFlow child) {
        sources.check();
        models.runner.checkTaskCancellation();
        if (!running()) throw new IllegalStateException("Flow request is no longer active");
        stack.pushNested(flow, child);
    }

    @Override
    public void pop() {
        sources.check();
        stack.pop(flow);
    }

    @Override
    public void finish() {
        sources.check();
        finished = true;
    }

    @Override
    public void beforeStep() {
        sources.check();
        models.runner.injectObservations();
    }

    @Override
    public boolean running() {
        return sources.active()
                && models.runner.control().state() == AgentState.RUNNING
                && models.runner.configurationRevision() == revision;
    }

    @Override
    public @NonNull ToolResult tool(@NonNull ToolCall call, @NonNull ActionContext context) {
        sources.check();
        return tools.executeOneCall(
                call, new ToolBatch(null, false, context), models.runner.toolInvocation());
    }

    @Override
    public ModelFlow.@NonNull Generated generate(
            ModelFlow.@NonNull ModelInput input, @NonNull ResponseContract contract) {
        sources.check();
        var exchange = models.generate(input, contract, tools);
        sources.register(exchange.citations());
        return new ModelFlow.Generated(
                exchange.response(), exchange.citations(), exchange.modelCallId());
    }

    @Override
    public void message(
            @NonNull String text, ModelFlow.Source citations, String callId, boolean forwarded) {
        models.output.emitMessage(
                text,
                sources.consume(citations, models.output.requestIdentity(), callId, text),
                callId,
                forwarded);
    }

    @Override
    public @NonNull String prompt(@NonNull String source, @NonNull Map<String, Object> data) {
        return PromptCompiler.compileText(source, data);
    }

    @Override
    public void observation(@NonNull String topic, @NonNull String text) {
        sources.check();
        models.output.appendObservation(topic, text);
    }

    @Override
    public String sourceCallId() {
        return callId;
    }

    /** Thread-safe, revocable set of source receipts which one work execution may forward once. */
    static final class GeneratedSourceReceipts {
        private final @NonNull Set<ModelFlow.Source> issued =
                Collections.newSetFromMap(new IdentityHashMap<>());
        private boolean closed;

        synchronized void check() {
            if (closed) throw new SecurityException("Generated-source work has completed");
        }

        synchronized boolean active() {
            return !closed;
        }

        synchronized void close() {
            closed = true;
            issued.clear();
        }

        synchronized void register(SourceEvidence.Receipt receipt) {
            check();
            if (receipt != null) issued.add(receipt);
        }

        synchronized MessageCitations.Bound consume(
                ModelFlow.Source receipt,
                @NonNull Object requestIdentity,
                String callId,
                @NonNull String message) {
            check();
            if (receipt == null) return null;
            if (!issued.contains(receipt))
                throw new SecurityException("Source receipt is not pending in this work");
            var bound = RequestEvidence.forRequest(receipt, requestIdentity, callId, message);
            issued.remove(receipt);
            return bound;
        }
    }
}
