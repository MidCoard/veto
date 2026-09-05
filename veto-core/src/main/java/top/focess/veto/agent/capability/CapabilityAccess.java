package top.focess.veto.agent.capability;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.tool.ToolCallContext;
import top.focess.veto.agent.tool.ToolCallContextHolder;
import top.focess.veto.agent.tool.ToolCapability;

/** Checks a capability operation before it can reach a resource or runtime service. */
public final class CapabilityAccess {
    private static final @NonNull ObjectMapper MAPPER = new ObjectMapper();

    private CapabilityAccess() {}

    public static @NonNull ToolCallContext require(
            @NonNull ToolCapability expected, @NonNull String toolName, @NonNull Object args) {
        ToolCallContext context = require(expected, toolName);
        var permit = context.executionPermit();
        Object screened;
        try {
            screened = MAPPER.convertValue(permit.screenedArguments(), args.getClass());
        } catch (IllegalArgumentException e) {
            throw denied();
        }
        if (!args.getClass().isRecord() || !args.equals(screened)) throw denied();
        return context;
    }

    /** Checks invocation authority for operations with no parameter-dependent effects. */
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
