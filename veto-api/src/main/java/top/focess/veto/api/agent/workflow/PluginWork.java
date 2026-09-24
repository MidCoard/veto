package top.focess.veto.api.agent.workflow;

import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.tool.ToolResult;
import top.focess.veto.api.llm.ResponseContract;
import top.focess.veto.api.llm.ToolCall;
import top.focess.veto.api.llm.VetoResponse;

/** A plugin owns its execution; the host grants only budgeted and authorized operations. */
@FunctionalInterface
public interface PluginWork {
    interface Source {}

    record ModelInput(
            @NonNull String prompt,
            @NonNull Map<String, Object> inputs,
            String modelTier,
            Double temperature,
            Boolean thought,
            @NonNull Set<String> allowedTools) {
        public ModelInput {
            allowedTools = Set.copyOf(allowedTools);
        }
    }

    record Generated(@NonNull VetoResponse response, Source citations, String modelCallId) {}

    interface Runtime {
        boolean running();

        void beforeStep();

        String sourceCallId();

        @NonNull ToolResult tool(@NonNull ToolCall call, @NonNull ActionContext context);

        @NonNull Generated generate(@NonNull ModelInput input, @NonNull ResponseContract contract);

        void message(@NonNull String text, Source citations, String callId, boolean forwarded);

        void observation(@NonNull String topic, @NonNull String text);

        @NonNull String prompt(@NonNull String source, @NonNull Map<String, Object> data);
    }

    void run(@NonNull Runtime runtime);
}
