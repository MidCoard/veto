package top.focess.veto.group;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.Agent;
import top.focess.veto.agent.AgentRunner;
import top.focess.veto.agent.AgentService;
import top.focess.veto.agent.identity.AgentPersona;
import top.focess.veto.llm.core.LlmOptions;
import top.focess.veto.llm.core.ProviderType;

/**
 * The production {@link GroupSpawner.AgentFactory} — builds a Mate's {@link Agent} via {@link
 * AgentService#createMate}. {@code @Lazy}-injects {@link AgentService} to break the cycle ({@code
 * GroupTools → GroupSpawner → AgentFactory → AgentService → McpEngine → GroupTools}).
 *
 * <p>The Mate's {@link AgentRunner.LlmBinding} is resolved from {@code veto.group.mate.*} config
 * (provider / model / credential-key / system-prompt-base). When the model or credential is unset,
 * the Mate's Agent is still created but its model calls fail at credential resolution (caught by
 * the Mate → FEEDBACK) — so a group spawns and runs even before a Mate model is configured; the
 * Mate just cannot accomplish work until {@code veto.group.mate.*} is set.
 */
@Component
public class GroupAgentFactory implements GroupSpawner.AgentFactory {

    private final AgentService agentService;
    private final ProviderType provider;
    private final String modelId;
    private final String credentialKey;
    private final String systemPromptBase;

    public GroupAgentFactory(
            @Lazy AgentService agentService,
            @Value("${veto.group.mate.provider:DEEPSEEK}") String provider,
            @Value("${veto.group.mate.model_id:#{null}}") String modelId,
            @Value("${veto.group.mate.credential-key:#{null}}") String credentialKey,
            @Value(
                            "${veto.group.mate.system-prompt-base:You are a Mate agent. Execute the assigned task.}")
                    String systemPromptBase) {
        this.agentService = agentService;
        this.provider = parseProvider(provider);
        this.modelId = modelId;
        this.credentialKey = credentialKey;
        this.systemPromptBase = systemPromptBase;
    }

    @Override
    public Agent create(AgentPersona persona) {
        String model = modelId != null ? modelId : persona.topModel();
        AgentRunner.LlmBinding binding =
                new AgentRunner.LlmBinding(
                        provider, model, credentialKey, LlmOptions.defaults(), systemPromptBase);
        return agentService.createMate(persona, binding);
    }

    private static ProviderType parseProvider(String s) {
        if (s == null || s.isBlank()) {
            return ProviderType.DEEPSEEK;
        }
        try {
            return ProviderType.valueOf(s);
        } catch (IllegalArgumentException e) {
            return ProviderType.DEEPSEEK;
        }
    }
}
