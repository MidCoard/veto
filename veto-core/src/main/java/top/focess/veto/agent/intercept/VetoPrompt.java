package top.focess.veto.agent.intercept;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.screening.Danger;

/**
 * The core HITL veto event emitted by the agent loop (the veto emission seam, parallel to the
 * user-facing message seam). A transport subscribes via {@code AgentService.submit}'s veto sink and
 * renders a picker from this domain event, mapping it to its own wire type at the transport edge.
 * The agent parks in {@link HitlRegistry} until the user's reply resolves it.
 *
 * <p>{@code agentId} is the runner's agent id (the persona id) - the {@link HitlRegistry} key the
 * veto is parked under - so a transport can resolve the veto directly without a session-key lookup.
 *
 * @param agentId the agent (persona) id - the HitlRegistry key the veto is parked under
 * @param callId the tool-call id the veto is parked under
 * @param tool the tool name being approved/refused
 * @param scenario the {@link VetoScenario} (drives display + grouping)
 * @param options the offered options (EDIT filtered out for v1 - a raw-string reply can't carry
 *     edited args); the user's reply must be one of these
 * @param args the call's arguments (display-only - the user approves the actual call)
 * @param danger the screening danger level (null when no screening produced it); transports warn
 *     the user prominently when it is DANGEROUS/CRITICAL
 */
public record VetoPrompt(
        @NonNull String agentId,
        @NonNull String callId,
        @NonNull String tool,
        @NonNull VetoScenario scenario,
        @NonNull List<VetoOption> options,
        @NonNull Map<String, Object> args,
        Danger danger) {
    /** Compact constructor: defensive copies + null-safe args. */
    public VetoPrompt {
        options = List.copyOf(options);
        args = Map.copyOf(args);
    }
}
