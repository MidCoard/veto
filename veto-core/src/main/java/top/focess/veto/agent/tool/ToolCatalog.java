package top.focess.veto.agent.tool;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import top.focess.veto.plugin.api.PluginState;
import top.focess.veto.plugin.contribution.Contribution;
import top.focess.veto.plugin.contribution.ContributionCatalog;
import top.focess.veto.plugin.contribution.ContributionId;
import top.focess.veto.plugin.contribution.ContributionPoint;
import top.focess.veto.plugin.contribution.ContributionSource;
import top.focess.veto.plugin.runtime.ScriptPlugin;

/**
 * Immutable host tool view built through the shared contribution layer. The private runtime
 * contract preserves existing capability-aware definitions; it is not an exported plugin SPI.
 */
final class ToolCatalog {
    private static final @NonNull ContributionPoint<RegisteredTool> TOOLS =
            new ContributionPoint<>(
                    new ContributionId("veto:runtime-tools"),
                    1,
                    ToolDocs.nonNullClass(RegisteredTool.class),
                    ContributionPoint.Cardinality.MULTIPLE);
    private static final @NonNull ContributionSource SOURCE =
            new ContributionSource("veto.runtime", "1", ContributionSource.Origin.BUILTIN);

    private final @NonNull List<RegisteredTool> registrations;
    private final @NonNull Map<String, RegisteredTool> byName;
    private final long generation;

    private ToolCatalog(@NonNull List<RegisteredTool> candidates, long generation) {
        var names = new LinkedHashMap<String, RegisteredTool>();
        var contributions = new ArrayList<Contribution<?>>();
        for (RegisteredTool registration : candidates) {
            String name = registration.definition().name();
            if (names.putIfAbsent(name, registration) != null)
                throw new IllegalArgumentException("Duplicate tool name: " + name);
            contributions.add(Contribution.of(TOOLS, internalId(name), registration));
        }
        ContributionCatalog catalog =
                new ContributionCatalog.Builder()
                        .define(TOOLS, ToolCatalog::validate)
                        .stage(SOURCE, contributions)
                        .freeze();
        // Derive lookup from the validated catalog, not the unvalidated candidate list.
        var validated = new LinkedHashMap<String, RegisteredTool>();
        for (var entry : catalog.entries(TOOLS)) {
            RegisteredTool registration = entry.implementation();
            validated.put(registration.definition().name(), registration);
        }
        this.registrations = List.copyOf(candidates);
        this.byName = Map.copyOf(validated);
        this.generation = generation;
    }

    static @NonNull ToolCatalog empty() {
        return new ToolCatalog(List.of(), 0);
    }

    /** The old snapshot and its definition/handler identities remain unchanged. */
    @NonNull ToolCatalog append(@NonNull List<RegisteredTool> additions) {
        if (additions.isEmpty()) return this;
        List<RegisteredTool> combined = new ArrayList<>(registrations);
        combined.addAll(additions);
        return new ToolCatalog(combined, Math.incrementExact(generation));
    }

    RegisteredTool resolve(@NonNull String name) {
        return byName.get(name);
    }

    long generation() {
        return generation;
    }

    @NonNull List<ToolDefinition> active(Set<String> whitelist) {
        List<ToolDefinition> definitions = new ArrayList<>();
        for (RegisteredTool registration : registrations) {
            ToolDefinition definition = registration.definition();
            if (registration instanceof RegisteredTool.Plugin plugin && !pluginAvailable(plugin))
                continue;
            if (registration instanceof RegisteredTool.Agent
                    || whitelist == null
                    || whitelist.contains(definition.name())) definitions.add(definition);
        }
        return List.copyOf(definitions);
    }

    /**
     * A plugin tool is advertised only while its lifecycle state is ACTIVE and, for script plugins,
     * the backing host process is still alive — matching {@code PluginController}'s liveness view.
     */
    private static boolean pluginAvailable(RegisteredTool.@NonNull Plugin plugin) {
        if (plugin.runtime().state() != PluginState.ACTIVE) return false;
        return !(plugin.runtime().implementation() instanceof ScriptPlugin script)
                || script.active();
    }

    private static void validate(@NonNull RegisteredTool registration) {
        switch (registration) {
            case RegisteredTool.Native nativeTool ->
                    ToolContractValidator.validateHandler(
                            nativeTool.handler(), nativeTool.definition());
            case RegisteredTool.Agent agentTool ->
                    ToolContractValidator.validateHandler(
                            agentTool.handler(), agentTool.definition());
            case RegisteredTool.Plugin plugin ->
                    ToolContractValidator.validate(plugin.definition());
            case RegisteredTool.Remote remoteTool ->
                    ToolContractValidator.validate(remoteTool.definition());
        }
    }

    /**
     * MCP wire names need not follow contribution-id syntax; preserve them verbatim in definitions.
     */
    private static @NonNull String internalId(@NonNull String name) {
        try {
            return "tool-"
                    + HexFormat.of()
                            .formatHex(
                                    MessageDigest.getInstance("SHA-256")
                                            .digest(name.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }
}
