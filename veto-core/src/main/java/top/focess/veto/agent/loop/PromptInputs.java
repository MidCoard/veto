package top.focess.veto.agent.loop;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.identity.AgentPersona;
import top.focess.veto.agent.screening.DeployerPolicy;
import top.focess.veto.agent.workspace.PathMode;
import top.focess.veto.agent.workspace.VetoMdResolver;
import top.focess.veto.agent.workspace.Workspace;
import top.focess.veto.api.agent.tool.ToolDocumentation;
import top.focess.veto.api.llm.ToolDefinition;
import top.focess.veto.api.llm.ToolResultPresentationMode;

/** Model inputs contain facts and executable contracts, never pre-rendered prompt sections. */
public final class PromptInputs {

    /** Bound argument/result examples per tool in the catalogue; the authoring standard is four. */
    private static final int MAX_CATALOG_EXAMPLES = 5;

    private PromptInputs() {}

    /**
     * Assembles the standard fact set bound into the system-prompt entry: persona, workspace, law
     * sources, guidance, environment, policy, presentation mode, and the tool catalogue.
     */
    public static @NonNull Map<String, Object> standard(
            @NonNull AgentPersona persona,
            @NonNull Workspace workspace,
            String guidance,
            @NonNull List<ToolDefinition> tools,
            @NonNull DeployerPolicy policy,
            @NonNull ToolResultPresentationMode presentation) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(
                "persona",
                new Persona(persona.name(), persona.description(), persona.role().name()));
        data.put("workspace", workspace(workspace));
        var lawSources = workspace.vetoMdResolver().sources();
        data.put(
                "law",
                lawSources.stream()
                        .map(VetoMdResolver.LawSource::content)
                        .collect(Collectors.joining("\n\n")));
        data.put(
                "lawSources",
                lawSources.stream()
                        .map(
                                source ->
                                        new LawSource(
                                                displayedRoot(source.root(), workspace.pathMode()),
                                                source.relativePath(),
                                                source.override(),
                                                source.content()))
                        .toList());
        data.put("guidance", guidance == null ? "" : guidance);
        data.put("environment", environment());
        data.put("policy", policy.name());
        data.put("presentation", presentation.name());
        data.put("tools", tools(tools));
        data.put("toolNames", tools.stream().map(ToolDefinition::name).toList());
        return data;
    }

    private static @NonNull String displayedRoot(@NonNull Path root, @NonNull PathMode mode) {
        if (mode != PathMode.VIRTUAL) return root.toString();
        Path name = root.getFileName();
        return "/" + (name == null ? "" : name.toString());
    }

    /** Host operating-system facts exposed to prompts. */
    public record Environment(@NonNull String os, @NonNull String arch, boolean windows) {}

    /** One mounted workspace root and whether it is the operational (current) root. */
    public record Root(
            @NonNull String hostPath, @NonNull String mountedPath, boolean operational) {}

    /** The workspace's path mode and its mounted roots. */
    public record WorkspaceInput(@NonNull String pathMode, @NonNull List<Root> roots) {}

    /** One tool's catalogue entry: flattened argument rows, examples, result formats, docs. */
    public record ToolInput(
            @NonNull String name,
            @NonNull String description,
            @NonNull List<ArgumentInput> arguments,
            @NonNull List<String> examples,
            @NonNull List<String> returnExamples,
            @NonNull List<Format> formats,
            @NonNull ToolDocumentation documentation) {}

    /** One flattened tool-argument row, with dotted/{@code []} paths for nested schemas. */
    public record ArgumentInput(
            @NonNull String name,
            @NonNull String type,
            @NonNull String itemType,
            boolean required,
            @NonNull String description) {}

    /** One tool result format the tool advertises. */
    public record Format(@NonNull String id, @NonNull String description) {}

    /** The agent's identity facts as bound into the prompt. */
    public record Persona(
            @NonNull String name, @NonNull String description, @NonNull String role) {}

    /** One resolved VETO.md law source: where it came from and its content. */
    public record LawSource(
            @NonNull String root,
            @NonNull String file,
            boolean override,
            @NonNull String content) {}

    /** Captures the current host's OS/arch facts. */
    public static @NonNull Environment environment() {
        String os = System.getProperty("os.name", "unknown");
        return new Environment(
                os,
                System.getProperty("os.arch", "unknown"),
                os.toLowerCase(Locale.ROOT).contains("win"));
    }

    /** Projects the workspace's path mode and roots into prompt input facts. */
    public static @NonNull WorkspaceInput workspace(@NonNull Workspace workspace) {
        Path operational = workspace.pathResolver().operationalRoot();
        return new WorkspaceInput(
                workspace.pathMode().name(),
                workspace.roots().stream()
                        .map(
                                root -> {
                                    Path name = root.hostPath().getFileName();
                                    return new Root(
                                            root.hostPath().toString(),
                                            "/" + (name == null ? "" : name.toString()),
                                            root.hostPath().equals(operational));
                                })
                        .toList());
    }

    /** Projects the tool catalogue into name-sorted prompt input rows, bounding example counts. */
    public static @NonNull List<ToolInput> tools(@NonNull List<ToolDefinition> tools) {
        return tools.stream()
                .sorted(Comparator.comparing(ToolDefinition::name))
                .map(
                        tool ->
                                new ToolInput(
                                        tool.name(),
                                        tool.description(),
                                        arguments(tool.inputSchema(), ""),
                                        tool.examples().stream()
                                                .limit(MAX_CATALOG_EXAMPLES)
                                                .toList(),
                                        tool.returnExamples().stream()
                                                .limit(MAX_CATALOG_EXAMPLES)
                                                .toList(),
                                        tool.resultFormats().stream()
                                                .map(
                                                        format ->
                                                                new Format(
                                                                        format.id(),
                                                                        format.description()))
                                                .toList(),
                                        tool.documentation()))
                .toList();
    }

    /**
     * Flattens a tool's effective input schema into per-argument rows for the catalogue. The schema
     * here is the same translated definition sent to the provider, so the rendered Args list and
     * the native schema never diverge. Nested objects and array items are listed with dotted and
     * {@code []} path prefixes.
     */
    private static @NonNull List<ArgumentInput> arguments(
            @NonNull Map<?, ?> schema, @NonNull String prefix) {
        List<ArgumentInput> result = new ArrayList<>();
        if (!(schema.get("properties") instanceof Map<?, ?> properties)) return result;
        Map<String, Object> ordered = new TreeMap<>();
        properties.forEach(
                (key, value) -> {
                    if (value != null) ordered.put(String.valueOf(key), value);
                });
        List<?> required = schema.get("required") instanceof List<?> list ? list : List.of();
        ordered.forEach(
                (key, value) -> {
                    if (!(value instanceof Map<?, ?> property)) return;
                    String name = prefix + key;
                    String type = property.get("type") instanceof String text ? text : "any";
                    Object rawItems = property.get("items");
                    String itemType =
                            rawItems instanceof Map<?, ?> itemSchema
                                            && itemSchema.get("type") instanceof String text
                                    ? text
                                    : "";
                    result.add(
                            new ArgumentInput(
                                    name,
                                    type,
                                    itemType,
                                    required.contains(key),
                                    property.get("description") instanceof String text
                                            ? text
                                            : ""));
                    result.addAll(arguments(property, name + "."));
                    if (property.get("items") instanceof Map<?, ?> items)
                        result.addAll(arguments(items, name + "[]."));
                });
        return result;
    }
}
