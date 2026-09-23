package top.focess.veto.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.intercept.Gateway;
import top.focess.veto.agent.loop.MessageCitations;
import top.focess.veto.agent.loop.ResponseEnforcer;
import top.focess.veto.agent.tool.LocalToolDefinition;
import top.focess.veto.agent.tool.NativeToolArgumentValidator;
import top.focess.veto.agent.tool.ResponseSubmissions;
import top.focess.veto.agent.tool.ToolCallContextHolder;
import top.focess.veto.agent.tool.ToolEngine;
import top.focess.veto.api.agent.tool.ResponseSubmission;
import top.focess.veto.api.agent.tool.ToolErrorCode;
import top.focess.veto.api.agent.tool.ToolExecutionException;
import top.focess.veto.api.agent.tool.ToolResultFormat;
import top.focess.veto.api.agent.tool.ToolResultStatus;
import top.focess.veto.api.agent.workflow.GenerateAction;
import top.focess.veto.api.agent.workflow.ResponseRequest;
import top.focess.veto.api.agent.workflow.ToolAction;
import top.focess.veto.api.llm.ToolCall;
import top.focess.veto.api.llm.VetoRequest;
import top.focess.veto.api.llm.VetoResponse;
import top.focess.veto.api.llm.exceptions.ModelSchemaException;

/** Validates model and submission responses before execution. */
final class ResponseValidator {
    private final @NonNull ToolEngine toolEngine;
    private final @NonNull ObjectMapper objectMapper;

    ResponseValidator(@NonNull ToolEngine tools, @NonNull ObjectMapper mapper) {
        this.toolEngine = tools;
        this.objectMapper = mapper;
    }

    ToolCallContextHolder.@NonNull ResponseDirective validateSubmission(
            @NonNull ResponseRequest submission,
            VetoRequest request,
            boolean submissionGeneration,
            String completionTool,
            @NonNull Set<String> whitelistedTools,
            @NonNull List<TurnRecord> history,
            @NonNull Gateway gateway)
            throws Exception {
        if (request == null)
            throw new IllegalStateException("No active model request for response submission");
        if (completionTool != null)
            throw new IllegalArgumentException("This agent must finish through " + completionTool);
        if (submission instanceof ResponseRequest.Plan plan) {
            try {
                if (submissionGeneration)
                    throw new IllegalArgumentException(
                            "Plan submission is unavailable in this context");
                var planDefinition =
                        request.tools().stream()
                                .filter(
                                        tool ->
                                                submissionKind(tool.name())
                                                        == ResponseSubmission.Kind.PLAN)
                                .findFirst()
                                .orElseThrow(
                                        () ->
                                                new IllegalArgumentException(
                                                        "Plan tool was not available in the current"
                                                                + " request"));
                NativeToolArgumentValidator.validateAgainstSchema(
                        planDefinition.name(),
                        objectMapper.createObjectNode().set("actions", plan.actions()),
                        objectMapper.valueToTree(planDefinition.inputSchema()));
                var program = plan.program();
                gateway.validateProgram(program, toolEngine, whitelistedTools, objectMapper);
                for (var action : program.actions()) {
                    if (action instanceof GenerateAction gen
                            && gen.responseMode() == GenerateAction.ResponseMode.CITATIONS
                            && request.tools().stream()
                                    .noneMatch(
                                            t ->
                                                    submissionKind(t.name())
                                                            == ResponseSubmission.Kind.ANSWER))
                        throw new IllegalArgumentException(
                                "CITATIONS generation requires an available answer submission"
                                        + " tool");
                    if (action instanceof ToolAction tool && submissionKind(tool.tool()) != null)
                        throw new IllegalArgumentException(
                                "Response submission tools cannot be nested as plan tool steps; use"
                                        + " generate for a cited answer and STOP to finish");
                }
                return new ToolCallContextHolder.ResponseDirective.Plan(program, plan.execution());
            } catch (IllegalArgumentException error) {
                throw new ToolExecutionException(
                        ToolResultStatus.FAILURE,
                        ToolResultFormat.PLAINTEXT,
                        ToolErrorCode.VALIDATION.INVALID_PLAN,
                        "Plan rejected before execution: " + error.getMessage());
            }
        }
        try {
            var answer = (ResponseRequest.Answer) submission;
            @NonNull VetoResponse response = MessageCitations.resolve(request, answer);
            ResponseEnforcer.enforce(response, whitelistedTools);
            MessageCitations.Bound bound = null;
            var citations = response.citations();
            if (citations != null) {
                bound = MessageCitations.bind(request, response, List.copyOf(history));
                for (var check : bound.checks()) {
                    for (var reference : check.references()) {
                        if (!reference.status().equals("matched"))
                            throw new IllegalArgumentException(
                                    "Citation "
                                            + check.id()
                                            + " could not match the exact quote in input message "
                                            + reference.messageIndex()
                                            + "; omit message_index and provide an exact quote from"
                                            + " a successful source. If the runtime returns"
                                            + " ambiguous candidates, select one of those"
                                            + " indices.");
                    }
                }
            }
            return new ToolCallContextHolder.ResponseDirective.Answer(response, bound);
        } catch (IllegalArgumentException | ModelSchemaException error) {
            throw new ToolExecutionException(
                    ToolResultStatus.FAILURE,
                    ToolResultFormat.PLAINTEXT,
                    ToolErrorCode.VALIDATION.INVALID_CITATION,
                    "Citation rejected: " + error.getMessage());
        }
    }

    ResponseSubmission.Kind submissionKind(@NonNull String name) {
        return ResponseSubmissions.kindOf(toolEngine.resolveDefinition(name));
    }

    void validateLocalCallArguments(@NonNull VetoResponse response) {
        var calls = response.calls();
        if (calls == null) return;
        for (var call : calls) {
            if (submissionKind(call.toolName()) != null) continue;
            if (toolEngine.resolveDefinition(call.toolName())
                    instanceof LocalToolDefinition local) {
                try {
                    NativeToolArgumentValidator.validate(
                            local.name(), objectMapper.valueToTree(call.args()), local.argsClass());
                } catch (ToolExecutionException invalid) {
                    throw new ModelSchemaException(
                            "native tool arguments must match the advertised argument schema for "
                                    + local.name()
                                    + ": "
                                    + invalid.getMessage());
                }
            }
        }
    }

    void validateResponseMode(@NonNull VetoResponse checked, @NonNull VetoRequest request) {
        var calls = checked.calls();
        request.responseContract()
                .validate(
                        request,
                        checked.message(),
                        calls == null
                                ? List.of()
                                : calls.stream().map(ToolCall::toolName).toList());
    }
}
