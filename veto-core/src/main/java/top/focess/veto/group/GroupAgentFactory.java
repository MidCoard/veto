package top.focess.veto.group;

import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.Agent;
import top.focess.veto.agent.AgentRunner;
import top.focess.veto.agent.AgentService;
import top.focess.veto.agent.identity.AgentPersona;
import top.focess.veto.agent.mcp.ToolCallContext;
import top.focess.veto.agent.mcp.ToolCallContextHolder;
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
        ModelBinding resolved = tierRegistry.resolve(owner, mateBinding.tier());
        String systemPromptBase = mateBinding.systemPromptBase();
        AgentRunner.LlmBinding binding =
                new AgentRunner.LlmBinding(
                        resolved.provider(),
                        resolved.model(),
                        resolved.credentialKey(),
                        new LlmOptions(
                                resolved.temperature(),
                                null,
                                resolved.maxOutputTokens(),
                                LlmOptions.defaults().timeout()),
                        systemPromptBase,
                        resolved.baseUrl());
        // Inherit the calling session's workspace so the Mate / one-shot Leader resolves paths +
        // matches grants against the same roots as the delegating agent. The calling agent's
        // persona id is available on the tool-call thread-local (set by AgentRunner around each
        // tool execute); fall back to the process default outside a tool-call scope (the lazy
        // provisioning path on the scheduler thread).
        ToolCallContext ctx = ToolCallContextHolder.get();
        Workspace workspace =
                ctx != null ? agentService.workspaceOf(ctx.agentId()) : agentService.workspace();
        // Outside a tool-call scope (lazy provisioning on the scheduler thread) the thread-local
        // userId is unavailable, so derive the Mate's memory-tenant userId from the owner carried
        // on
        // the MateBinding — same user as the Leader, not the shared placeholder.
        UUID userId = ctx != null ? ctx.userId() : agentService.userIdForOwner(owner);
        return agentService.createMate(persona, binding, userId, owner, workspace);
    }
}
