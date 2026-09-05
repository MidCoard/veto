package top.focess.veto.agent.capability;

import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.tool.ToolCallContext;
import top.focess.veto.agent.tool.ToolCallContextHolder;
import top.focess.veto.agent.tool.ToolCapability;

/** Checks a capability operation before it can reach a resource or runtime service. */
public final class CapabilityAccess {

    private CapabilityAccess() {}

    /** Checks invocation authority for the named operation. */
    public static @NonNull ToolCallContext require(
            @NonNull ToolCapability expected, @NonNull String toolName) {
        ToolCallContext context = require(expected);
        if (!context.executionPermit().toolName().equals(toolName)) throw denied();
        return context;
    }

    public static @NonNull ToolCallContext require(@NonNull ToolCapability expected) {
        ToolCallContext context = ToolCallContextHolder.get();
        if (context == null) throw denied();
        var permit = context.executionPermit();
        if (permit.capability() != expected
                || !permit.callId().equals(ToolCallContextHolder.currentCallId())
                || !permit.authorizesCaller(context)) throw denied();
        return context;
    }

    private static @NonNull SecurityException denied() {
        return new SecurityException(
                "This tool call is not authorized for the current session; submit a fresh call.");
    }
}
