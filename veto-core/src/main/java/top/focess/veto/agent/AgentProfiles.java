package top.focess.veto.agent;

import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.identity.AgentPersona;
import top.focess.veto.agent.identity.Role;
import top.focess.veto.agent.tool.ToolDefinition;
import top.focess.veto.api.llm.LlmBinding;
import top.focess.veto.api.plugin.agent.AgentProfile;
import top.focess.veto.api.plugin.contract.AgentConfiguration;
import top.focess.veto.model.tier.ModelTier;
import top.focess.veto.model.tier.ModelTierRegistry;
import top.focess.veto.util.Nullness;

/** Resolves generic configuration intent against host-owned model and tool authority. */
public final class AgentProfiles {
    private AgentProfiles() {}

    /**
     * Projects a host {@link ToolDefinition} into the tool view exposed to configuration intent.
     */
    public static AgentConfiguration.@NonNull Tool configurationTool(@NonNull ToolDefinition tool) {
        var source = tool.provenance();
        return new AgentConfiguration.Tool(
                tool.name(),
                tool.capability(),
                source == null ? null : source.pluginId(),
                source == null ? null : source.localId());
    }

    /** A configuration intent resolved against host-owned persona, model binding and prompt. */
    public record Resolved(
            @NonNull AgentPersona persona,
            @NonNull LlmBinding binding,
            AgentProfile.Prompt prompt) {}

    /**
     * Resolves a requested {@link AgentProfile} against the tools the host actually authorizes and
     * the owner's model tiers, rejecting any request for tools outside {@code authorized}.
     *
     * @throws SecurityException if the profile requests tools not present in {@code authorized}
     */
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
        var prompt = profile.prompt();
        var tier = profile.tier();
        if (tier == null)
            return new Resolved(
                    persona,
                    new LlmBinding(
                            base.provider(),
                            base.model(),
                            base.credentialKey(),
                            base.options(),
                            base.baseUrl()),
                    prompt);
        var model = tiers.resolve(owner, Nullness.requireNonNull(ModelTier.valueOf(tier)));
        return new Resolved(
                persona,
                new LlmBinding(
                        model.provider(),
                        model.model(),
                        model.credentialKey(),
                        model.llmOptions(),
                        model.baseUrl()),
                prompt);
    }
}
