package top.focess.veto.group;

import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.AgentRunner;
import top.focess.veto.llm.core.LlmOptions;
import top.focess.veto.model.tier.ModelBinding;
import top.focess.veto.model.tier.ModelTier;
import top.focess.veto.model.tier.ModelTierRegistry;

/**
 * The Leader's {@link AgentRunner.LlmBinding} - the model the STANDALONE agent is promoted to when
 * it transforms into the Leader of a new group. Resolved from the {@link ModelTierRegistry} for the
 * tier named by {@code veto.group.leader.tier} (default {@code TOP}); the concrete provider / model
 * / credential come from the active model-tier profile, so switching profiles swaps the Leader's
 * model without touching this binding. Only the system-prompt base stays here (role-specific).
 *
 * <p>Kept separate from {@link GroupAgentFactory} (the Mate factory) so the transform path does not
 * depend on the AgentService-bound Mate wiring: {@code create_group} resolves the Leader binding
 * without constructing a Mate, and the binding is resolvable in a unit test without an {@link
 * top.focess.veto.agent.AgentService}.
 */
@Component
public class LeaderBinding {

    private final @NonNull ModelTierRegistry tierRegistry;
    private final @NonNull ModelTier tier;
    private final @NonNull String systemPromptBase;

    public LeaderBinding(
            @Value("${veto.group.leader.tier:TOP}") String tier,
            @Value(
                            "${veto.group.leader.system-prompt-base:Decompose the task and arrange the execution DAG node by node.}")
                    @NonNull String systemPromptBase,
            @NonNull ModelTierRegistry tierRegistry) {
        this.tierRegistry = tierRegistry;
        this.tier = parseTier(tier);
        this.systemPromptBase = systemPromptBase;
    }

    /**
     * The Leader's model binding (provider / model / credential / options / system-prompt base),
     * resolved from the owner's active model-tier profile for this binding's tier. The owner is the
     * session username (read from the calling agent's {@link
     * top.focess.veto.agent.mcp.ToolCallContext} at {@code create_group} time).
     */
    public AgentRunner.@NonNull LlmBinding binding(@NonNull String owner) {
        ModelBinding resolved = tierRegistry.resolve(owner, tier);
        return new AgentRunner.LlmBinding(
                resolved.provider(),
                resolved.model(),
                resolved.credentialKey(),
                LlmOptions.defaults(),
                systemPromptBase,
                resolved.baseUrl());
    }

    private static @NonNull ModelTier parseTier(String s) {
        if (s == null || s.isBlank()) {
            return ModelTier.TOP;
        }
        try {
            return top.focess.veto.util.Nullness.requireNonNull(ModelTier.valueOf(s));
        } catch (IllegalArgumentException e) {
            return ModelTier.TOP;
        }
    }
}
