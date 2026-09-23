package top.focess.veto.agent.capability;

import java.time.Instant;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.tool.ToolCallContext;
import top.focess.veto.api.agent.capability.MonitorCapability;
import top.focess.veto.api.agent.tool.ToolCapability;
import top.focess.veto.api.agent.tool.ToolErrorCode;
import top.focess.veto.api.agent.tool.ToolErrors;
import top.focess.veto.monitor.MonitorService;

@Component
public final class MonitorCapabilityImpl implements MonitorCapability {
    private final @NonNull MonitorService service;

    public MonitorCapabilityImpl(@NonNull MonitorService service) {
        this.service = service;
    }

    private @NonNull ToolCallContext context(@NonNull String tool) {
        return CapabilityAccess.require(ToolCapability.MONITOR_CONTROL, tool);
    }

    @Override
    public @NonNull Object create(@NonNull String purpose, @NonNull Instant due) {
        var ctx = context("create_monitor");
        String owner = ctx.owner();
        var session = ctx.sessionId();
        if (owner == null || session == null) {
            return ToolErrors.failure(
                    ToolErrorCode.SESSION.NO_SESSION_CONTEXT,
                    "Monitor not created: no active session context.");
        }
        return service.createTimer(
                owner, session.toString(), ctx.agentId(), purpose, due, ctx.requestId());
    }

    @Override
    public @NonNull Object inspect() {
        var ctx = context("inspect_monitor");
        String owner = ctx.owner();
        var session = ctx.sessionId();
        if (owner == null || session == null) {
            return ToolErrors.failure(
                    ToolErrorCode.SESSION.NO_SESSION_CONTEXT,
                    "Monitors not listed: no active session context.");
        }
        return service.list(owner, session.toString()).stream()
                .filter(r -> r.agentId().equals(ctx.agentId()))
                .toList();
    }

    @Override
    public @NonNull Object control(@NonNull String id, @NonNull String operation) {
        var ctx = context(operation + "_monitor");
        String owner = ctx.owner();
        var session = ctx.sessionId();
        if (owner == null || session == null) {
            return ToolErrors.failure(
                    ToolErrorCode.SESSION.NO_SESSION_CONTEXT,
                    "Monitor not updated: no active session context.");
        }
        return service.control(owner, session.toString(), ctx.agentId(), id, operation);
    }
}
