package top.focess.veto.api.agent.tool;

import org.jspecify.annotations.NonNull;

/** A record-authored host tool. Origin does not select its authority or execution path. */
public interface NativeTool<T> extends CapabilityTool<T> {
    @Override
    default @NonNull ToolCapability getCapability() {
        ToolSecurity security = getClass().getAnnotation(ToolDocs.nonNullClass(ToolSecurity.class));
        if (security == null) throw new IllegalArgumentException("Missing ToolSecurity annotation");
        return security.capability();
    }

    default @NonNull String getDescription() {
        return ToolDocs.descriptionOf(getClass());
    }
}
