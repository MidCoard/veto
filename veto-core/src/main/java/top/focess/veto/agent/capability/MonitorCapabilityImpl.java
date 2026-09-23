package top.focess.veto.agent.capability;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.DateTimeException;
import java.time.Instant;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.tool.ToolCallContext;
import top.focess.veto.agent.tool.ToolErrorCode;
import top.focess.veto.agent.tool.ToolErrors;
import top.focess.veto.api.agent.tool.ToolCapability;
import top.focess.veto.monitor.MonitorService;

@Component
public final class MonitorCapabilityImpl implements MonitorCapability {
    private final @NonNull MonitorService service;
    private final @NonNull ObjectMapper mapper;

    public MonitorCapabilityImpl(@NonNull MonitorService service, @NonNull ObjectMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    private @NonNull ToolCallContext context(@NonNull String tool) {
        return CapabilityAccess.require(ToolCapability.MONITOR_CONTROL, tool);
    }

    @Override
    public @NonNull String create(@NonNull String purpose, Long afterSeconds, String at) {
        var ctx = context("create_monitor");
        if ((afterSeconds == null) == (at == null)) {
            return ToolErrors.failure(
                    ToolErrorCode.VALIDATION.INVALID_ARGUMENTS,
                    "Monitor not created: supply exactly one of afterSeconds or at.");
        }
        Instant due;
        if (afterSeconds != null) {
            due = Instant.now().plusSeconds(afterSeconds);
        } else {
            try {
                due = Instant.parse(at.strip());
            } catch (DateTimeException error) {
                return ToolErrors.failure(
                        ToolErrorCode.VALIDATION.INVALID_ARGUMENTS,
                        "Monitor not created: at must be an ISO-8601 timestamp with an offset, such as 2026-09-20T07:30:00+08:00.");
            }
        }
        if (purpose.isBlank()) {
            return ToolErrors.failure(
                    ToolErrorCode.VALIDATION.INVALID_ARGUMENTS,
                    "Monitor not created: purpose must not be blank.");
        }
        Instant now = Instant.now();
        if (!due.isAfter(now) || due.isAfter(now.plusSeconds(30L * 24 * 3600))) {
            return ToolErrors.failure(
                    ToolErrorCode.VALIDATION.INVALID_ARGUMENTS,
                    "Monitor not created: choose a future time within 30 days.");
        }
        String owner = ctx.owner();
        var session = ctx.sessionId();
        if (owner == null || session == null) {
            return ToolErrors.failure(
                    ToolErrorCode.SESSION.NO_SESSION_CONTEXT,
                    "Monitor not created: no active session context.");
        }
        try {
            return json(
                    service.createTimer(
                            owner,
                            session.toString(),
                            ctx.agentId(),
                            purpose,
                            due,
                            ctx.requestId()));
        } catch (IllegalStateException error) {
            return ToolErrors.failure(
                    ToolErrorCode.MONITOR.LIMIT_EXCEEDED,
                    "Monitor not created: this agent already has 32 active or paused monitors.");
        }
    }

    @Override
    public @NonNull String inspect() {
        var ctx = context("inspect_monitor");
        String owner = ctx.owner();
        var session = ctx.sessionId();
        if (owner == null || session == null) {
            return ToolErrors.failure(
                    ToolErrorCode.SESSION.NO_SESSION_CONTEXT,
                    "Monitors not listed: no active session context.");
        }
        return json(
                service.list(owner, session.toString()).stream()
                        .filter(r -> r.agentId().equals(ctx.agentId()))
                        .toList());
    }

    @Override
    public @NonNull String control(@NonNull String id, @NonNull String operation) {
        var ctx = context(operation + "_monitor");
        String owner = ctx.owner();
        var session = ctx.sessionId();
        if (owner == null || session == null) {
            return ToolErrors.failure(
                    ToolErrorCode.SESSION.NO_SESSION_CONTEXT,
                    "Monitor not updated: no active session context.");
        }
        try {
            return json(service.control(owner, session.toString(), ctx.agentId(), id, operation));
        } catch (SecurityException error) {
            return ToolErrors.failure(
                    ToolErrorCode.MONITOR.UNKNOWN,
                    "Monitor not found: no accessible monitor has id " + id + ".");
        } catch (IllegalArgumentException error) {
            return ToolErrors.failure(
                    ToolErrorCode.MONITOR.GROUP_MANAGED,
                    "Monitor not updated: group observation monitors follow the group lifecycle and cannot be paused, resumed, or cancelled directly.");
        }
    }

    private @NonNull String json(@NonNull Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            return ToolErrors.failure(
                    ToolErrorCode.RESULT.ENCODING_FAILED,
                    "Encoding failed: could not encode the monitor result.");
        }
    }
}
