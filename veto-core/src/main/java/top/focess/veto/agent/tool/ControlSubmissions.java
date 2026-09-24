package top.focess.veto.agent.tool;

import top.focess.veto.api.agent.tool.ControlSubmission;
import top.focess.veto.api.agent.tool.ToolDocs;

public final class ControlSubmissions {
    private ControlSubmissions() {}

    public static ControlSubmission.Kind kindOf(ToolDefinition definition) {
        if (!(definition instanceof LocalToolDefinition local)) return null;
        var annotation =
                local.toolClass().getAnnotation(ToolDocs.nonNullClass(ControlSubmission.class));
        return annotation == null ? null : annotation.value();
    }
}
