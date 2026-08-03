package top.focess.veto.agent.screening;

import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.intercept.HitlRegistry;
import top.focess.veto.agent.intercept.VetoScenario;

/**
 * The deterministic screening result for one native/remote tool call: (relevance, danger) + the
 * scenario + reason. The {@link HitlRegistry} resolves it via the screening mode matrix.
 */
public record Screening(
        @NonNull Relevance relevance,
        @NonNull Danger danger,
        @NonNull VetoScenario scenario,
        @NonNull String reason) {}
