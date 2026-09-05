package top.focess.veto.agent.capability;

import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.tool.ToolCapability;

/** Resolves an unforgeable call-scoped capability from the current screened tool context. */
public final class CapabilityResolver {

    private CapabilityResolver() {}

    public static <C extends Capability> @NonNull C require(@NonNull Class<C> capabilityType) {
        Capability capability;
        if (capabilityType == WorkspaceReadCapability.class) {
            capability =
                    new WorkspaceReadCapabilityImpl(
                            CapabilityAccess.require(ToolCapability.WORKSPACE_READ)
                                    .executionPermit());
        } else if (capabilityType == WorkspaceWriteCapability.class) {
            capability =
                    new WorkspaceWriteCapabilityImpl(
                            CapabilityAccess.require(ToolCapability.WORKSPACE_WRITE)
                                    .executionPermit());
        } else {
            throw new SecurityException(
                    "Unsupported tool capability type: " + capabilityType.getName());
        }
        return capabilityType.cast(capability);
    }
}
