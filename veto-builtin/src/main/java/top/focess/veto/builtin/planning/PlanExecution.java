package top.focess.veto.builtin.planning;

import java.util.Map;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.tool.ToolResult;
import top.focess.veto.api.agent.workflow.PluginWork;
import top.focess.veto.api.agent.workflow.PluginWork.Source;
import top.focess.veto.api.llm.ResponseContract;
import top.focess.veto.api.llm.ToolCall;
import top.focess.veto.api.llm.VetoResponse;

/** A plugin-owned execution continuation. Host operations retain normal authority and budgets. */
public interface PlanExecution {
    record Generated(@NonNull VetoResponse response, Source citations, String modelCallId) {}

    interface Runtime {
        boolean running();

        default void beforeStep() {}

        @NonNull ToolResult tool(@NonNull ToolCall call, @NonNull PlanStepContext context);

        @NonNull Generated generate(
                @NonNull GenerateAction action, @NonNull ResponseContract contract);

        void message(@NonNull String text, Source citations, String modelCallId, boolean forwarded);

        void escaped(@NonNull String reason);

        @NonNull String prompt(@NonNull String source, @NonNull Map<String, Object> data);
    }

    @NonNull PluginWork accepted(@NonNull ActionsProgram program);

    @NonNull Scope scope();

    boolean active();

    void install(@NonNull ActionsProgram program, String modelCallId);

    void step(@NonNull Runtime runtime);

    void run(@NonNull Runtime runtime);
}
