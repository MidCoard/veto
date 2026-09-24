package top.focess.veto.agent;

import top.focess.veto.agent.tool.ToolCallContextHolder;
import top.focess.veto.api.agent.workflow.ActionContext;

/** Call-stack-local provenance and the single control result produced by this tool batch. */
final class ToolBatch {
    final ModelExchange.Result exchange;
    final boolean generation;
    final ActionContext step;
    ToolCallContextHolder.ResponseDirective control;

    ToolBatch(ModelExchange.Result exchange, boolean generation, ActionContext step) {
        this.exchange = exchange;
        this.generation = generation;
        this.step = step;
    }

    String modelCallId() {
        return step != null
                ? step.sourceCallId()
                : exchange == null ? null : exchange.modelCallId();
    }
}
