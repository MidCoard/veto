package top.focess.veto.agent.tool;

import top.focess.veto.api.agent.tool.ResponseSubmission;
import top.focess.veto.api.agent.tool.ToolDocs;

public final class ResponseSubmissions {
    private ResponseSubmissions() {}

    public static ResponseSubmission.Kind kindOf(ToolDefinition definition) {
        if (!(definition instanceof LocalToolDefinition local)) return null;
        var annotation =
                local.toolClass().getAnnotation(ToolDocs.nonNullClass(ResponseSubmission.class));
        return annotation == null ? null : annotation.value();
    }
}
