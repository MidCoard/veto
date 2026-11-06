package top.focess.veto.secret;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.veto.api.agent.tool.CapabilityTool;
import top.focess.veto.api.agent.tool.ToolSecurity;
import top.focess.veto.api.event.BeforeTextCommitEvent;
import top.focess.veto.api.plugin.PluginContext;
import top.focess.veto.api.plugin.PluginIdentity;
import top.focess.veto.api.plugin.PluginState;
import top.focess.veto.api.plugin.Scope;
import top.focess.veto.api.plugin.contract.JsonValue;
import top.focess.veto.api.plugin.contract.StandardContributionPoints;
import top.focess.veto.api.plugin.contribution.Contribution;

class SecretProtectionPluginTest {
    private @NonNull List<Contribution<?>> initialize() {
        var entries = new ArrayList<@NonNull Contribution<?>>();
        var context =
                new PluginContext(
                        new PluginIdentity("top.focess.secret-protection", "1.0.100"),
                        () -> {},
                        () -> PluginState.ACTIVE,
                        Map.of(),
                        Map.of(
                                StandardContributionPoints.FRONTEND, entries::add,
                                StandardContributionPoints.LISTENERS, entries::add,
                                StandardContributionPoints.TOOLS, entries::add,
                                StandardContributionPoints.AGENT_CONFIGURATION, entries::add));
        new SecretProtectionPlugin(context, new JsonValue.ObjectValue(Map.of()));
        return List.copyOf(entries);
    }

    @Test
    void contributesOrdinaryToolsAndAgentPolicyWithoutGlobalMasking() {
        var entries = initialize();
        assertEquals(
                Set.of("veto:frontend", "veto:listeners", "veto:tools", "veto:agent-configuration"),
                entries.stream()
                        .map(entry -> entry.point().id().value())
                        .collect(Collectors.toSet()));
        assertEquals(
                Set.of("import_credential", "read_credential"),
                entries.stream()
                        .map(Contribution::implementation)
                        .flatMap(
                                value ->
                                        value instanceof CapabilityTool<?> tool
                                                ? Stream.of(tool.getName())
                                                : Stream.empty())
                        .collect(Collectors.toSet()));
        var importPolicy = ImportCredentialTool.class.getAnnotation(ToolSecurity.class);
        var readPolicy = ReadCredentialTool.class.getAnnotation(ToolSecurity.class);
        if (importPolicy == null || readPolicy == null)
            throw new AssertionError("Missing tool policy");
        assertTrue(importPolicy.explicitSelectionOnly());
        assertFalse(readPolicy.explicitSelectionOnly());
    }

    @Test
    void missingAgentHostCancelsPublicationInsteadOfPassingRawInput() {
        var listener = listener();
        var event =
                new BeforeTextCommitEvent(
                        new Scope.AgentScope(UUID.randomUUID(), "session", "primary"),
                        BeforeTextCommitEvent.Phase.INPUT,
                        "input",
                        "password=synthetic");
        assertThrows(Exception.class, () -> listener.onTextCommit(event));
        assertTrue(event.isCancelled());
    }

    private @NonNull SecretProtectionListener listener() {
        for (var entry : initialize()) {
            if (entry.implementation() instanceof SecretProtectionListener listener)
                return listener;
        }
        throw new AssertionError("Missing listener");
    }
}
