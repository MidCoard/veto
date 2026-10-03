package top.focess.veto.agent;

import java.util.ArrayDeque;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.loop.PromptCompiler;
import top.focess.veto.api.agent.AgentState;
import top.focess.veto.api.agent.tool.ToolResult;
import top.focess.veto.api.agent.workflow.ActionContext;
import top.focess.veto.api.agent.workflow.ModelFlow;
import top.focess.veto.api.llm.ResponseContract;
import top.focess.veto.api.llm.ToolCall;
import top.focess.veto.plugin.runtime.ManagedPluginFlow;

/** Runner-thread-owned flow selection; an empty stack selects the core default flow. */
final class ModelFlowStack {
    private static final int MAX_DEPTH = 8;
    private final @NonNull ArrayDeque<@NonNull ModelFlow> frames = new ArrayDeque<>();

    @NonNull ModelFlow top() {
        ModelFlow top = frames.peek();
        if (top == null) throw new IllegalStateException("Core default flow is selected");
        return top;
    }

    boolean defaultSelected() {
        return frames.isEmpty();
    }

    void push(@NonNull ModelFlow flow) {
        if (frames.size() >= MAX_DEPTH)
            throw new IllegalStateException("Model flow stack depth exceeded");
        frames.push(flow);
    }

    void pushNested(@NonNull ModelFlow parent, @NonNull ModelFlow child) {
        requireTop(parent);
        push(parent instanceof ManagedPluginFlow owned ? owned.child(child) : child);
    }

    void pop(@NonNull ModelFlow flow) {
        requireTop(flow);
        frames.pop();
    }

    void discardFailed(@NonNull ModelFlow flow) {
        if (frames.stream().noneMatch(frame -> frame == flow)) return;
        ModelFlow removed;
        do {
            removed = frames.pop();
        } while (removed != flow);
    }

    /** Each run gets a fresh, revocable runtime even when the selected flow persists. */
    boolean run(@NonNull ModelSession models, @NonNull AgentToolExecution tools, String callId) {
        var flow = top();
        var scope = new Step(models, tools, flow, callId);
        try {
            flow.run(scope);
            return scope.finished || !defaultSelected() && top() == flow;
        } catch (RuntimeException | Error failure) {
            discardFailed(flow);
            throw failure;
        } finally {
            scope.sources.close();
        }
    }

    private final class Step implements ModelFlow.Runtime {
        private final @NonNull ModelSession models;
        private final @NonNull AgentToolExecution tools;
        private final @NonNull ModelFlow flow;
        private final String callId;
        private final long revision;
        private final RequestEvidence.@NonNull WorkSources sources =
                new RequestEvidence.WorkSources();
        private boolean finished;

        Step(
                @NonNull ModelSession models,
                @NonNull AgentToolExecution tools,
                @NonNull ModelFlow flow,
                String callId) {
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
            pushNested(flow, child);
        }

        @Override
        public void pop() {
            sources.check();
            ModelFlowStack.this.pop(flow);
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
                @NonNull String text,
                ModelFlow.Source citations,
                String callId,
                boolean forwarded) {
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
    }

    private void requireTop(@NonNull ModelFlow flow) {
        if (frames.peek() != flow)
            throw new IllegalStateException("Only the selected top flow may change the stack");
    }
}
