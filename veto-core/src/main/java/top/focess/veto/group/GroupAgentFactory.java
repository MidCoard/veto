package top.focess.veto.group;

import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.Agent;
import top.focess.veto.agent.AgentRunner;
import top.focess.veto.agent.AgentService;
import top.focess.veto.agent.identity.AgentPersona;
import top.focess.veto.agent.workspace.Workspace;
import top.focess.veto.llm.core.LlmOptions;
import top.focess.veto.model.tier.ModelBinding;
import top.focess.veto.model.tier.ModelTierRegistry;

/**
 * The production {@link GroupSpawner.AgentFactory} - builds a Mate's {@link Agent} via {@link
 * AgentService#createMate}. {@code @Lazy}-injects {@link AgentService} to break the cycle ({@code
 * GroupTools → GroupSpawner → AgentFactory → AgentService → ToolEngine → GroupTools}).
 *
 * <p>The Mate's {@link AgentRunner.LlmBinding} is resolved from the {@link ModelTierRegistry} for
 * the tier carried by the {@link MateBinding} (chosen per-skillset by {@link GroupSpawner}); the
 * concrete provider / model / credential come from the owner's active model-tier profile, so
 * switching profiles swaps every Mate's model at once. The system-prompt base is role-specific and
 * carried on the MateBinding.
 */
@Component
public class GroupAgentFactory implements GroupSpawner.AgentFactory {

    private final @NonNull AgentService agentService;
    private final @NonNull ModelTierRegistry tierRegistry;

    public GroupAgentFactory(
            @Lazy @NonNull AgentService agentService, @NonNull ModelTierRegistry tierRegistry) {
        this.agentService = agentService;
        this.tierRegistry = tierRegistry;
    }

    @Override
    public @NonNull Agent create(@NonNull AgentPersona persona, @NonNull MateBinding mateBinding) {
        // The owner (session username) is carried on the MateBinding because lazy Mate provisioning
        // runs on the GroupTickScheduler thread, outside any tool-call scope - the thread-local
        // ToolCallContext is unavailable there. It is stamped on the Group at create_group time and
        // flows here via GroupSpawner.startMate → resolveMateBinding. Resolving against the owner's
        // active profile means a Mate runs on the same user's configured model as the Leader.
        String owner = mateBinding.owner();
        if (owner == null) {
            throw new IllegalStateException(
                    "Cannot provision a group agent without the session owner");
        }
        AgentRunner.LlmBinding binding = resolveBinding(owner, mateBinding);
        Workspace workspace = mateBinding.workspace();
        if (workspace == null) {
            throw new IllegalStateException(
                    "Cannot provision a group agent without the session workspace");
        }
        UUID userId = agentService.userIdForOwner(owner);
        return agentService.createMate(
                persona, binding, userId, owner, workspace, mateBinding.toolResultPresentation());
    }

    private AgentRunner.@NonNull LlmBinding resolveBinding(
            @NonNull String owner, @NonNull MateBinding mateBinding) {
        ModelBinding resolved = tierRegistry.resolve(owner, mateBinding.tier());
        return new AgentRunner.LlmBinding(
                resolved.provider(),
                resolved.model(),
                resolved.credentialKey(),
                new LlmOptions(
                        resolved.temperature(),
                        null,
                        resolved.maxOutputTokens(),
                        LlmOptions.defaults().timeout()),
                mateBinding.systemPromptBase(),
                resolved.baseUrl());
    }
}
