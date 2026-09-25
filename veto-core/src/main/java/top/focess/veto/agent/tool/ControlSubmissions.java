package top.focess.veto.agent.tool;

import top.focess.veto.api.agent.tool.ControlSubmission;
import top.focess.veto.api.agent.tool.ToolDocs;

/** Reads the {@link ControlSubmission} kind declared by a tool's implementation class. */
public final class ControlSubmissions {
    private ControlSubmissions() {}

    /**
     * The control-submission kind declared via {@link ControlSubmission} on a local tool's
     * implementation class, or {@code null} if the definition is not local or carries no such
     * annotation.
     */
    public static ControlSubmission.Kind kindOf(ToolDefinition definition) {
        if (!(definition instanceof LocalToolDefinition local)) return null;
        var annotation =
                local.toolClass().getAnnotation(ToolDocs.nonNullClass(ControlSubmission.class));
        return annotation == null ? null : annotation.value();
    }
}
