package top.focess.veto.agent.capability;

import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.intercept.ToolExecutionPermit;
import top.focess.veto.agent.tool.ToolCallContext;
import top.focess.veto.agent.tool.ToolCallContextHolder;

/** Resolves an unforgeable call-scoped capability from the current screened tool context. */
public final class CapabilityResolver {

    private CapabilityResolver() {}

    public static <C extends Capability> @NonNull C require(@NonNull Class<C> capabilityType) {
        ToolCallContext context = ToolCallContextHolder.get();
        if (context == null) {
            throw new SecurityException("Capability requires an authorized tool-call context");
        }
        ToolExecutionPermit permit = context.executionPermit();
        Capability capability;
        if (capabilityType == WorkspaceReadCapability.class) {
            capability = new WorkspaceReadCapabilityImpl(permit);
        } else if (capabilityType == WorkspaceWriteCapability.class) {
            capability = new WorkspaceWriteCapabilityImpl(permit);
        } else {
            throw new SecurityException(
                    "Unsupported tool capability type: " + capabilityType.getName());
        }
        return capabilityType.cast(capability);
    }
}
