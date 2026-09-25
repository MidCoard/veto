package top.focess.veto.api.agent.tool;

import org.jspecify.annotations.NonNull;

/**
 * A record-authored host tool. Origin does not select its authority or execution path.
 *
 * @param <T> immutable argument value decoded by the host
 */
public interface NativeTool<T> extends CapabilityTool<T> {
    /**
     * Reads the required effect category from {@link ToolSecurity} on the implementation class.
     *
     * @return the declared execution capability
     * @throws IllegalArgumentException when the annotation is absent
     */
    @Override
    default @NonNull ToolCapability getCapability() {
        ToolSecurity security = getClass().getAnnotation(ToolDocs.nonNullClass(ToolSecurity.class));
        if (security == null) throw new IllegalArgumentException("Missing ToolSecurity annotation");
        return security.capability();
    }

    /**
     * Returns the short model-visible description declared by {@link ToolDoc}, or an empty string.
     *
     * @return the short description
     */
    default @NonNull String getDescription() {
        return ToolDocs.descriptionOf(getClass());
    }
}
