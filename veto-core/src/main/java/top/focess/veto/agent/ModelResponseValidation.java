package top.focess.veto.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.tool.ControlSubmissions;
import top.focess.veto.agent.tool.LocalToolDefinition;
import top.focess.veto.agent.tool.NativeToolArgumentValidator;
import top.focess.veto.agent.tool.ToolEngine;
import top.focess.veto.api.agent.tool.ControlSubmission;
import top.focess.veto.api.agent.tool.ToolExecutionException;
import top.focess.veto.api.llm.ToolCall;
import top.focess.veto.api.llm.VetoRequest;
import top.focess.veto.api.llm.VetoResponse;
import top.focess.veto.api.llm.exceptions.ModelSchemaException;

/** Validates model and submission responses before execution. */
final class ModelResponseValidation {
    private final @NonNull ToolEngine toolEngine;
    private final @NonNull ObjectMapper objectMapper;

    ModelResponseValidation(@NonNull ToolEngine tools, @NonNull ObjectMapper mapper) {
        this.toolEngine = tools;
        this.objectMapper = mapper;
    }

    ControlSubmission.Kind submissionKind(@NonNull String name) {
        return ControlSubmissions.kindOf(toolEngine.resolveDefinition(name));
    }

    void validateBase(@NonNull VetoResponse response, @NonNull Set<String> tools) {
        var calls = response.calls();
        if (calls != null) {
            if (calls.isEmpty())
                throw new ModelSchemaException("calls must be non-empty when present");
            for (var call : calls)
                if (!tools.contains(call.toolName()))
                    throw new ModelSchemaException(
                            "calls[].tool_name must exactly name a catalog tool; '"
                                    + call.toolName()
                                    + "' is not in this turn's tool catalog");
        }
        var message = response.message();
        if (calls == null && (message == null || message.isBlank()))
            throw new ModelSchemaException("message required (no native tool calls to execute)");
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
