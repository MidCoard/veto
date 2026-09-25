package top.focess.veto.builtin.planning;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.control.ControlHost;
import top.focess.veto.api.agent.tool.*;

/** {@code submit_plan} - validate and install a complete actions program for execution. */
@ControlSubmission(ControlSubmission.Kind.EXECUTE)
@ToolPrompt("plan-system-prompt")
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
                "Success: JSON {\"status\":\"accepted\"}. A failed result (INVALID_PLAN) contains `Plan rejected before execution: <detail>` and means the plan was not accepted; correct it and resubmit. Acceptance does not mean its steps have already succeeded.",
        errorsAndEdgeCases =
                "Each action needs a unique id, a label and a type. Finish with STOP. Tool actions require tool, inputs and outputs. Generate actions require prompt and outputs; inputs supplies named data independently of optional prompt placeholders. Its response_mode selects an output channel from the native schema and defaults to TEXT, which returns the requested content without tool calls. outputs is a variable-binding map, never configuration: each key names a new variable and each value selects a result field. Generate exposes message; {\"answer\":\"message\"} binds its text to $answer. Read bound values with $name; action indices are zero-based. Use only catalog tools and respect their argument schemas. Cycles need an exit, and all steps consume budgets. The final result comes from STOP.result_binding; without it the runtime returns accumulated bindings.",
        security =
                "Available only in plan-enabled sessions and for authorized callers. Every plan tool step retains its own permission and workspace checks. Submitting a plan grants no extra authority.",
        resultFormats = {ToolResultFormat.JSON},
        examples = {
            "{\"actions\":[{\"id\":\"greet\",\"label\":\"Write greeting\",\"type\":\"generate\",\"prompt\":\"Write a brief welcome message\",\"outputs\":{\"answer\":\"message\"}},{\"id\":\"finish\",\"label\":\"Return greeting\",\"type\":\"STOP\",\"result_binding\":\"answer\"}]}",
            "{\"actions\":[{\"id\":\"read\",\"label\":\"Read the config file\",\"type\":\"tool\",\"tool\":\"view_file\",\"inputs\":{\"absolutePath\":\"/workspace/app.conf\"},\"outputs\":{\"config\":\"text\"}},{\"id\":\"summarize\",\"label\":\"Summarize the config\",\"type\":\"generate\",\"prompt\":\"Summarize the key settings in the supplied configuration.\",\"inputs\":{\"config\":\"$config\"},\"outputs\":{\"answer\":\"message\"}},{\"id\":\"finish\",\"label\":\"Return the summary\",\"type\":\"STOP\",\"result_binding\":\"answer\"}]}",
            "{\"actions\":[{\"id\":\"test\",\"label\":\"Run the test suite\",\"type\":\"tool\",\"tool\":\"run_command\",\"inputs\":{\"commands\":[{\"executable\":\"gradle\",\"args\":[\"test\"]}]},\"outputs\":{\"result\":\"text\"}},{\"id\":\"check\",\"label\":\"Check the test outcome\",\"type\":\"conditional_goto\",\"check\":{\"kind\":\"exit_ok\",\"step_id\":\"test\"},\"true_goto\":2,\"false_goto\":4},{\"id\":\"passed\",\"label\":\"Report the passing run\",\"type\":\"generate\",\"prompt\":\"Confirm that the test suite passed.\",\"outputs\":{\"answer\":\"message\"}},{\"id\":\"done\",\"label\":\"Return the result\",\"type\":\"STOP\",\"result_binding\":\"answer\"},{\"id\":\"failed\",\"label\":\"Summarize the failures\",\"type\":\"generate\",\"prompt\":\"Summarize the failures in the supplied test output.\",\"inputs\":{\"output\":\"$result\"},\"outputs\":{\"answer\":\"message\"}},{\"id\":\"stop\",\"label\":\"Return the failure summary\",\"type\":\"STOP\",\"result_binding\":\"answer\"}]}",
            "{\"actions\":[{\"id\":\"draft\",\"label\":\"Draft the release notes\",\"type\":\"generate\",\"prompt\":\"Draft concise release notes for version 1.1.\",\"outputs\":{\"notes\":\"message\"},\"model_tier\":\"TOP\",\"temperature\":0.3},{\"id\":\"finish\",\"label\":\"Return the notes\",\"type\":\"STOP\",\"result_binding\":\"notes\"}]}",
            "{\"actions\":[{\"id\":\"greet\",\"label\":\"Write greeting\",\"type\":\"generate\",\"prompt\":\"Write a brief welcome message\",\"outputs\":{\"answer\":\"message\"}},{\"id\":\"greet\",\"label\":\"Repeat greeting\",\"type\":\"generate\",\"prompt\":\"Repeat the greeting\",\"outputs\":{\"again\":\"message\"}},{\"id\":\"finish\",\"label\":\"Return greeting\",\"type\":\"STOP\",\"result_binding\":\"answer\"}]}"
        },
        returnExamples = {
            "{\"status\":\"accepted\"}",
            "{\"status\":\"accepted\"}",
            "{\"status\":\"accepted\"}",
            "{\"status\":\"accepted\"}",
            "Plan rejected before execution: duplicate action id: greet"
        })
public final class SubmitPlanTool implements ControlTool<SubmitPlanTool.Args> {
    private final ControlHost capability;
    private final @NonNull PlanConfig configuration;
    private static final @NonNull ObjectMapper MAPPER = new ObjectMapper();

    /** Declaration-only instance; the host supplies the capability at execution time. */
    public SubmitPlanTool() {
        this(null, PlanConfig.defaults());
    }

    /** Declaration-only instance with the given plan configuration. */
    public SubmitPlanTool(@NonNull PlanConfig configuration) {
        this(null, configuration);
    }

    /** Creates an instance bound to the given control host with default configuration. */
    public SubmitPlanTool(@NonNull ControlHost capability) {
        this(capability, PlanConfig.defaults());
    }

    /** Creates an instance bound to the given control host and configuration. */
    public SubmitPlanTool(ControlHost capability, @NonNull PlanConfig configuration) {
        this.capability = capability;
        this.configuration = configuration;
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
    public @NonNull ControlHost controlHost() {
        if (capability == null) throw new SecurityException("Host must supply tool capability");
        return capability;
    }

    @Override
    public @NonNull String execute(@NonNull Args args, @NonNull ControlHost capability)
            throws Exception {
        var actions = MAPPER.valueToTree(args.actions());
        try {
            var program = ActionsProgramParser.parse(actions);
            ProgramValidator.validate(program);
            ProgramValidator.validateInputs(program);
            var answerTool = PlanPreflight.validate(program, capability, MAPPER);
            capability.execute(
                    new PlanProgram(MAPPER, configuration, answerTool).accepted(program));
        } catch (IllegalArgumentException | ProgramValidator.InvalidProgramException error) {
            throw new ToolExecutionException(
                    ToolResultStatus.FAILURE,
                    ToolResultFormat.PLAINTEXT,
                    ToolErrorCode.VALIDATION.INVALID_PLAN,
                    "Plan rejected before execution: " + error.getMessage());
        }
        return "{\"status\":\"accepted\"}";
    }

    /** Model-facing arguments of {@code submit_plan}. */
    @ToolInputSchema(PlanProgramSchema.class)
    public record Args(
            @NonNull @Doc("Complete ordered plan, ending with STOP.")
                    List<@NonNull Map<String, Object>> actions) {}
}
