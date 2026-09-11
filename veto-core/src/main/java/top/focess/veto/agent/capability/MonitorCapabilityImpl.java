package top.focess.veto.agent.capability;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.tool.ToolCallContext;
import top.focess.veto.agent.tool.ToolCapability;
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
        Instant due;
        if (afterSeconds != null && at == null) due = Instant.now().plusSeconds(afterSeconds);
        else if (at != null && afterSeconds == null) due = Instant.parse(at);
        else throw new IllegalArgumentException("Supply exactly one of afterSeconds or at");
        String owner = ctx.owner();
        var session = ctx.sessionId();
        if (owner == null || session == null) throw new SecurityException("No active Session");
        return json(
                service.createTimer(
                        owner, session.toString(), ctx.agentId(), purpose, due, ctx.requestId()));
    }

    @Override
    public @NonNull String inspect() {
        var ctx = context("inspect_monitor");
        String owner = ctx.owner();
        var session = ctx.sessionId();
        if (owner == null || session == null) throw new SecurityException("No active Session");
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
        if (owner == null || session == null) throw new SecurityException("No active Session");
        return json(service.control(owner, session.toString(), ctx.agentId(), id, operation));
    }

    private @NonNull String json(@NonNull Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Cannot render Monitor", error);
        }
    }
}
