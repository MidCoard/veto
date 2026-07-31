package top.focess.veto.group;

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
 * GroupTools -> GroupSpawner -> AgentFactory -> AgentService -> ToolEngine -> GroupTools}).
 *
 * <p>The Mate's {@link AgentRunner.LlmBinding} is resolved from the {@link ModelTierRegistry} for
 * the tier carried by the {@link MateBinding} (chosen per-skillset by {@link GroupSpawner}); the
 * concrete provider / model / credential come from the active model-tier profile, so switching
 * profiles swaps every Mate's model at once. The system-prompt base is role-specific and carried on
 * the MateBinding.
 */
@Component
public class GroupAgentFactory implements GroupSpawner.AgentFactory {

    private final @NonNull AgentService agentService;
    private final @NonNull ModelTierRegistry tierRegistry;

    public GroupAgentFactory(@Lazy AgentService agentService, ModelTierRegistry tierRegistry) {
        this.agentService = agentService;
        this.tierRegistry = tierRegistry;
    }

    @Override
    public @NonNull Agent create(@NonNull AgentPersona persona, @NonNull MateBinding mateBinding) {
        ModelBinding resolved = tierRegistry.resolve(mateBinding.tier());
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
                        systemPromptBase);
        // Inherit the calling session's workspace so the Mate / one-shot Leader resolves paths +
        // matches grants against the same roots as the delegating agent. The calling agent's
        // persona id is available on the tool-call thread-local (set by AgentRunner around each
        // tool execute); fall back to the process default outside a tool-call scope.
        ToolCallContext ctx = ToolCallContextHolder.get();
        Workspace workspace =
                ctx != null ? agentService.workspaceOf(ctx.agentId()) : agentService.workspace();
        return agentService.createMate(persona, binding, workspace);
    }
}
