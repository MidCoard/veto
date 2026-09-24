package top.focess.veto.agent;

import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.identity.AgentPersona;
import top.focess.veto.agent.identity.Role;
import top.focess.veto.agent.loop.PromptCompiler;
import top.focess.veto.agent.tool.ToolDefinition;
import top.focess.veto.api.llm.LlmBinding;
import top.focess.veto.api.plugin.agent.AgentProfile;
import top.focess.veto.api.plugin.contract.AgentConfiguration;
import top.focess.veto.api.plugin.contract.JsonValues;
import top.focess.veto.model.tier.ModelTier;
import top.focess.veto.model.tier.ModelTierRegistry;
import top.focess.veto.util.Nullness;

/** Resolves generic configuration intent against host-owned model and tool authority. */
public final class AgentProfiles {
    private AgentProfiles() {}

    public static AgentConfiguration.@NonNull Tool configurationTool(@NonNull ToolDefinition tool) {
        var source = tool.provenance();
        return new AgentConfiguration.Tool(
                tool.name(),
                tool.capability(),
                source == null ? null : source.pluginId(),
                source == null ? null : source.localId());
    }

    public record Resolved(@NonNull AgentPersona persona, @NonNull LlmBinding binding) {}

    public static @NonNull Resolved resolve(
            @NonNull String id,
            @NonNull String owner,
            @NonNull AgentProfile profile,
            @NonNull Set<ToolDefinition> authorized,
            @NonNull LlmBinding base,
            @NonNull ModelTierRegistry tiers) {
        Set<String> available =
                authorized.stream().map(ToolDefinition::name).collect(Collectors.toSet());
        if (!available.containsAll(profile.tools()))
            throw new SecurityException("Configuration requests unavailable tools");
        var tools =
                authorized.stream()
                        .filter(tool -> profile.tools().contains(tool.name()))
                        .collect(Collectors.toSet());
        var persona =
                new AgentPersona(
                        id,
                        profile.name(),
                        profile.description(),
                        tools,
                        Role.valueOf(profile.label()));
        String guidance = base.systemPromptBase();
        var prompt = profile.prompt();
        if (prompt != null)
            guidance =
                    PromptCompiler.compileText(prompt.resource(), JsonValues.toMap(prompt.data()));
        var tier = profile.tier();
        if (tier == null)
            return new Resolved(
                    persona,
                    new LlmBinding(
                            base.provider(),
                            base.model(),
                            base.credentialKey(),
                            base.options(),
                            guidance,
                            base.baseUrl()));
        var model = tiers.resolve(owner, Nullness.requireNonNull(ModelTier.valueOf(tier)));
        return new Resolved(
                persona,
                new LlmBinding(
                        model.provider(),
                        model.model(),
                        model.credentialKey(),
                        model.llmOptions(),
                        guidance,
                        model.baseUrl()));
    }
}
