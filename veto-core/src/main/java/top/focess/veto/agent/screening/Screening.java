package top.focess.veto.agent.screening;

import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.intercept.HitlRegistry;
import top.focess.veto.agent.intercept.VetoScenario;

/**
 * The effective screening result for one native/remote tool call. {@code slmEvaluated}
 * distinguishes a real semantic relevance/danger judgment from deterministic-only operation; the
 * Gateway may still use HIGH as a conservative matrix input when that flag is false. The {@link
 * HitlRegistry} resolves the result via the screening mode matrix.
 */
public record Screening(
        @NonNull Relevance relevance,
        @NonNull Danger danger,
        boolean slmEvaluated,
        @NonNull VetoScenario scenario,
        @NonNull String reason) {}
