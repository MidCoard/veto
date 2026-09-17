package top.focess.veto.agent.tool.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.capability.LoopControlCapability;
import top.focess.veto.agent.tool.*;

@Component
@ResponseSubmission(ResponseSubmission.Kind.PLAN)
@ToolDoc(
        description =
                "Submit a known executable workflow directly, including its reads as tool steps, generated content, branches and a final STOP. Resolve only missing prerequisites before submission. Keep source evidence in generate.inputs and put the transformation goal in its prompt; do not rewrite source facts into new instructions. Call this tool alone.",
        behavior =
                "Validates the complete plan and its allowed tools before accepting it. On success, the runtime executes the plan under the session's normal approvals and budgets. Acceptance starts execution; this is not a draft for review.",
        whenToUse =
                "Use when the next steps and data dependencies are known, especially a sequence with conditional branches or bounded repetition. Honor an explicit request for plan execution. Submit a known workflow directly, putting its reads and other operations inside the plan.",
        whenNotToUse =
                "Use ordinary tools first only to resolve missing prerequisites needed to construct the workflow. A known read can be a plan step without first reading the same target separately. Reply in text when the task is complete. Call this tool alone; it transfers control to the submitted plan.",
        resultContract =
                "Success: JSON {\"status\":\"accepted\"}. A failed result contains a diagnostic and means the plan was not accepted; correct it and resubmit. Acceptance does not mean its steps have already succeeded.",
        errorsAndEdgeCases =
                "Each action needs a unique id, a label and a type. Finish with STOP. Tool actions require tool, inputs and outputs. Generate actions require prompt and outputs; inputs supplies named data independently of optional prompt placeholders. Its response_mode defaults to TEXT, which returns the requested content without tool calls; choose CITATIONS only when the requested output needs clickable conversation-source references. outputs is a variable-binding map, never configuration: each key names a new variable and each value selects a result field. Generate exposes message; {\"answer\":\"message\"} binds its text to $answer. Read bound values with $name; action indices are zero-based. Use only catalog tools and respect their argument schemas. Cycles need an exit, and all steps consume budgets. The final result comes from STOP.result_binding; without it the runtime returns accumulated bindings.",
        security =
                "Available only in plan-enabled sessions and for authorized callers. Every plan tool step retains its own permission and workspace checks. Submitting a plan grants no extra authority.",
        resultFormats = {ToolResultFormat.JSON},
        examples = {
            "{\"actions\":[{\"id\":\"greet\",\"label\":\"Write greeting\",\"type\":\"generate\",\"prompt\":\"Write a brief welcome message\",\"outputs\":{\"answer\":\"message\"}},{\"id\":\"finish\",\"label\":\"Return greeting\",\"type\":\"STOP\",\"result_binding\":\"answer\"}]}"
        },
        returnExamples = {"{\"status\":\"accepted\"}"})
public final class SubmitPlanTool implements LoopControlTool<SubmitPlanTool.Args> {
    private final @NonNull LoopControlCapability capability;
    private static final @NonNull ObjectMapper MAPPER = new ObjectMapper();

    public SubmitPlanTool(@NonNull LoopControlCapability capability) {
        this.capability = capability;
    }

    @Override
    public @NonNull String getName() {
        return "submit_plan";
    }

    @Override
    public @NonNull Class<Args> getArgsClass() {
        return ToolDocs.nonNullClass(Args.class);
    }

    @Override
    public @NonNull LoopControlCapability loopControlCapability() {
        return capability;
    }

    @Override
    public @NonNull String execute(@NonNull Args args, @NonNull LoopControlCapability capability)
            throws Exception {
        capability.submitPlan(MAPPER.valueToTree(args.actions()));
        return "{\"status\":\"accepted\"}";
    }

    @ToolInputSchema(PlanProgramSchema.class)
    public record Args(
            @NonNull @Doc("Complete ordered plan, ending with STOP.")
                    List<@NonNull Map<String, Object>> actions) {}
}
