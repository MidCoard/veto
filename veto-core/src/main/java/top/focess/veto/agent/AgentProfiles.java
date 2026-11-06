package top.focess.veto.agent;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.identity.AgentPersona;
import top.focess.veto.agent.identity.Role;
import top.focess.veto.agent.tool.ToolDefinition;
import top.focess.veto.agent.tool.ToolEngine;
import top.focess.veto.api.llm.LlmBinding;
import top.focess.veto.api.plugin.agent.AgentProfile;
import top.focess.veto.api.plugin.contract.AgentConfiguration;
import top.focess.veto.integration.plugins.SessionPlugins;
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

    /**
     * Authorized execution inputs. The persona is a prompt/tool profile or a construction template
     * for a new agent, never a replacement for an existing agent identity.
     */
    public record Resolved(
            @NonNull AgentPersona persona, @NonNull LlmBinding binding, String prompt) {}

    record Selection(@NonNull Resolved resolved, AgentConfiguration.Transition transition) {}

    static @NonNull Selection select(
            @NonNull AgentPersona persona,
            @NonNull LlmBinding binding,
            SessionPlugins plugins,
            @NonNull UUID userId,
            @NonNull String session,
            @NonNull String agentId,
            @NonNull ToolEngine engine,
            ModelTierRegistry tiers,
            @NonNull String task) {
        if (plugins == null) return new Selection(new Resolved(persona, binding, null), null);
        var requested = new HashSet<>(engine.getActiveTools(null));
        requested.addAll(
                engine.getActiveTools(
                        persona.whitelistedTools().stream()
                                .map(ToolDefinition::name)
                                .collect(Collectors.toSet())));
        var available = plugins.tools(session, Set.copyOf(requested));
        var base =
                new AgentProfile(
                        persona.name(),
                        persona.description(),
                        persona.role().name(),
                        persona.whitelistedTools().stream()
                                .map(ToolDefinition::name)
                                .collect(Collectors.toSet()),
                        null,
                        null,
                        Map.of());
        var configuration =
                plugins.configure(
                        userId,
                        session,
                        agentId,
                        persona.configurationOwner(),
                        base,
                        available.stream().map(AgentProfiles::configurationTool).toList(),
                        task);
        if (configuration == null)
            return new Selection(
                    new Resolved(
                            persona.withWhitelistedTools(
                                    plugins.tools(session, persona.whitelistedTools())),
                            binding,
                            null),
                    null);
        if (tiers == null) throw new IllegalStateException("Model tiers unavailable");
        return new Selection(
                resolve(
                        agentId,
                        userId,
                        configuration.profile(),
                        available,
                        binding,
                        tiers,
                        configuration.prompt()),
                configuration.transition());
    }

    /**
     * Resolves a requested {@link AgentProfile} against the tools the host actually authorizes and
     * the userId's model tiers, rejecting any request for tools outside {@code authorized}.
     *
     * @throws SecurityException if the profile requests tools not present in {@code authorized}
     */
    public static @NonNull Resolved resolve(
            @NonNull String id,
            @NonNull UUID userId,
            @NonNull AgentProfile profile,
            @NonNull Set<ToolDefinition> authorized,
            @NonNull LlmBinding base,
            @NonNull ModelTierRegistry tiers,
            String prompt) {
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
        var modelBinding = profile.model();
        if (modelBinding != null) return new Resolved(persona, modelBinding, prompt);
        var tier = profile.tier();
        if (tier == null) return new Resolved(persona, base, prompt);
        var model = tiers.resolve(userId, Nullness.requireNonNull(ModelTier.valueOf(tier)));
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
