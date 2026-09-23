package top.focess.veto.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.intercept.GuidedStepContext;
import top.focess.veto.agent.loop.ActionsProgram;
import top.focess.veto.agent.loop.Check;
import top.focess.veto.agent.loop.CheckEvaluator;
import top.focess.veto.agent.loop.ConditionalGotoAction;
import top.focess.veto.agent.loop.GenerateAction;
import top.focess.veto.agent.loop.GotoAction;
import top.focess.veto.agent.loop.MessageCitations;
import top.focess.veto.agent.loop.PromptCompiler;
import top.focess.veto.agent.loop.Scope;
import top.focess.veto.agent.loop.StopAction;
import top.focess.veto.agent.loop.ToolAction;
import top.focess.veto.agent.tool.ToolResult;
import top.focess.veto.api.llm.ResponseContract;
import top.focess.veto.api.llm.ToolCall;
import top.focess.veto.api.llm.VetoResponse;

/** One accepted program, its bindings and provenance. Uses the runner's normal tool/model path. */
final class GuidedProgram {
    interface Runtime {
        boolean running();

        @NonNull ToolResult tool(@NonNull ToolCall call, @NonNull GuidedStepContext context);

        @NonNull Generated generate(
                @NonNull GenerateAction action, @NonNull ResponseContract contract);

        void message(
                @NonNull String text,
                MessageCitations.Bound citations,
                String modelCallId,
                boolean forwarded);

        void escaped(@NonNull String reason);
    }

    record Generated(
            @NonNull VetoResponse response, MessageCitations.Bound citations, String modelCallId) {}

    private record GeneratedCitation(
            @NonNull Scope scope,
            @NonNull String message,
            MessageCitations.Bound bound,
            String modelCallId) {}

    private final @NonNull ObjectMapper objectMapper;
    private @NonNull Scope scope;
    private ActionsProgram activeProgram;
    private int programCounter;
    private int currentSteps;
    private int maxGuidedSteps;
    private String programModelCallId;
    private final @NonNull Map<String, String> guidedSources = new HashMap<>();
    private final @NonNull Map<String, GeneratedCitation> generatedCitations = new HashMap<>();

    GuidedProgram(@NonNull ObjectMapper mapper) {
        objectMapper = mapper;
        scope = new Scope(mapper);
    }

    void configure(int maxSteps) {
        if (maxSteps < 1) throw new IllegalArgumentException("guided max-steps must be positive");
        maxGuidedSteps = maxSteps;
    }

    @NonNull Scope scope() {
        return scope;
    }

    boolean active() {
        return activeProgram != null;
    }

    void reset() {
        activeProgram = null;
        programCounter = 0;
        currentSteps = 0;
        scope = new Scope(objectMapper);
        guidedSources.clear();
        generatedCitations.clear();
        programModelCallId = null;
    }

    private void escape(@NonNull Runtime runtime, @NonNull String reason) {
        activeProgram = null;
        programCounter = 0;
        runtime.escaped(
                "Guided mode exited: "
                        + reason
                        + ". Scope preserved with "
                        + scope.size()
                        + " bindings.");
    }

    void install(@NonNull ActionsProgram program, String modelCallId) {
        scope = new Scope(objectMapper);
        generatedCitations.clear();
        guidedSources.clear();
        activeProgram = program;
        programModelCallId = modelCallId;
        programCounter = 0;
        currentSteps = 0;
    }

    void step(@NonNull Runtime runtime) {
        ActionsProgram program = activeProgram;
        if (program == null) return;
        if (programCounter < 0 || programCounter >= program.actions().size()) {
            escape(runtime, "program counter out of bounds");
            return;
        }
        var action = program.actions().get(programCounter);
        if (++currentSteps > maxGuidedSteps) {
            escape(runtime, "step limit exceeded");
            throw new IllegalStateException("Guided program exceeded its execution step limit");
        }
        scope.put("CURRENT_STEPS", currentSteps);
        switch (action) {
            case ToolAction tool -> {
                @NonNull ToolCall call = new ToolCall(tool.tool(), tool.resolveInputs(scope));
                @NonNull ToolResult result;

                @NonNull Map<String, String> sources =
                        GuidedStepContext.sources(objectMapper, tool.inputs(), guidedSources);
                @NonNull GuidedStepContext context =
                        new GuidedStepContext(
                                programModelCallId,
                                tool.id(),
                                programCounter,
                                tool.label(),
                                sources);
                result = runtime.tool(call, context);
                if (activeProgram != program) {
                    return; // The tool replaced the role and cleared this program and scope.
                }
                scope.put("step_ok:" + tool.id(), result.success());
                scope.bindTool(tool.outputs(), result);
                tool.outputs()
                        .keySet()
                        .forEach(key -> guidedSources.put(key, tool.id() + ":" + call.callId()));
                if (tool.outputs() != null)
                    tool.outputs().keySet().forEach(generatedCitations::remove);
                programCounter++;
                if (!result.success() && runtime.running()) {
                    boolean handled =
                            programCounter < program.actions().size()
                                    && program.actions().get(programCounter)
                                            instanceof ConditionalGotoAction next
                                    && next.check() instanceof Check.ExitOk check
                                    && check.stepId().equals(tool.id());
                    if (!handled)
                        throw new IllegalStateException(
                                "Guided tool failed: " + tool.tool() + ": " + result.content());
                }
            }
            case GenerateAction gen -> {
                @NonNull Generated generated = runtime.generate(gen, ResponseContract.generation());
                @NonNull VetoResponse response = generated.response();
                scope.bindGenerate(gen.outputs(), response);
                gen.outputs()
                        .keySet()
                        .forEach(
                                key ->
                                        guidedSources.put(
                                                key, gen.id() + ":" + generated.modelCallId()));
                String generatedMessage = response.message();
                MessageCitations.Bound generatedSources = generated.citations();
                if (gen.outputs() != null) {
                    for (Map.Entry<String, String> output : gen.outputs().entrySet()) {
                        generatedCitations.remove(output.getKey());
                        if ("message".equals(output.getValue()) && generatedMessage != null)
                            generatedCitations.put(
                                    output.getKey(),
                                    new GeneratedCitation(
                                            scope,
                                            generatedMessage,
                                            generatedSources,
                                            generated.modelCallId()));
                    }
                }
                scope.put("step_ok:" + gen.id(), true);
                programCounter++;
            }
            case GotoAction gt -> programCounter = gt.index();
            case ConditionalGotoAction cg -> {
                boolean passed;
                if (cg.check() instanceof Check.Llm check) {
                    @NonNull GenerateAction judgment =
                            new GenerateAction(
                                    cg.id(),
                                    cg.label(),
                                    PromptCompiler.compileText(
                                            "runtime-judgment", Map.of("prompt", check.prompt())),
                                    Map.of(
                                            "judgment_input",
                                            "$" + check.var().replaceFirst("^\\$", "")),
                                    Map.of(),
                                    false,
                                    null,
                                    0.0);
                    String answer =
                            runtime.generate(judgment, ResponseContract.predicate())
                                    .response()
                                    .message();
                    if (answer == null
                            || !(answer.strip().equals("true") || answer.strip().equals("false")))
                        throw new IllegalArgumentException(
                                "Semantic check must return true or false");
                    passed = Boolean.parseBoolean(answer.strip());
                } else passed = CheckEvaluator.evaluate(cg.check(), scope, currentSteps);
                programCounter = cg.nextPc(passed, programCounter + 1);
            }
            case StopAction stop -> {
                String resultBinding = stop.resultBinding();
                @NonNull String result =
                        resultBinding != null
                                ? scope.opt(resultBinding)
                                        .map(Object::toString)
                                        .orElseThrow(
                                                () ->
                                                        new IllegalArgumentException(
                                                                "Unbound STOP result: "
                                                                        + resultBinding))
                                : scope.synthesize();
                GeneratedCitation citation =
                        resultBinding == null ? null : generatedCitations.get(resultBinding);
                boolean matches =
                        citation != null
                                && citation.scope() == scope
                                && citation.message().equals(result);
                runtime.message(
                        result,
                        citation != null && matches ? citation.bound() : null,
                        citation != null && matches ? citation.modelCallId() : null,
                        citation == null);
                generatedCitations.clear();
                activeProgram = null;
                programCounter = 0;
                return;
            }
            default -> {
                escape(runtime, "unknown action: " + action);
                return;
            }
        }
    }
}
