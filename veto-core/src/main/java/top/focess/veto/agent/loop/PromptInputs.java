package top.focess.veto.agent.loop;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.identity.AgentPersona;
import top.focess.veto.agent.screening.DeployerPolicy;
import top.focess.veto.agent.workspace.Workspace;
import top.focess.veto.llm.core.ToolDefinition;
import top.focess.veto.llm.core.ToolResultPresentationMode;

/** Model inputs contain facts and executable contracts, never pre-rendered prompt sections. */
public final class PromptInputs {
    private PromptInputs() {}

    public static @NonNull Map<String, Object> standard(
            @NonNull AgentPersona persona,
            @NonNull Workspace workspace,
            String guidance,
            @NonNull List<ToolDefinition> tools,
            @NonNull DeployerPolicy policy,
            @NonNull ToolResultPresentationMode presentation,
            boolean guided) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(
                "persona",
                Map.of(
                        "name",
                        persona.name(),
                        "description",
                        persona.description(),
                        "role",
                        persona.role().name()));
        data.put("workspace", workspace(workspace));
        String law = workspace.vetoMdResolver().resolve();
        data.put("law", law);
        data.put("guidance", guidance == null ? "" : guidance);
        data.put("environment", environment());
        data.put("policy", policy.name());
        data.put("presentation", presentation.name());
        data.put("guided", guided);
        data.put("tools", tools(tools));
        data.put("toolNames", tools.stream().map(ToolDefinition::name).toList());
        data.put(
                "skills",
                persona.registeredSkills().stream()
                        .map(
                                skill ->
                                        Map.of(
                                                "name",
                                                skill.name(),
                                                "description",
                                                skill.description()))
                        .toList());
        return data;
    }

    public static @NonNull Map<String, Object> environment() {
        String os = System.getProperty("os.name", "unknown");
        return Map.of(
                "os",
                os,
                "arch",
                System.getProperty("os.arch", "unknown"),
                "windows",
                os.toLowerCase(Locale.ROOT).contains("win"));
    }

    public static @NonNull Map<String, Object> workspace(@NonNull Workspace workspace) {
        Path operational = workspace.pathResolver().operationalRoot();
        return Map.of(
                "pathMode",
                workspace.pathMode().name(),
                "roots",
                workspace.roots().stream()
                        .map(
                                root -> {
                                    Path name = root.hostPath().getFileName();
                                    return Map.of(
                                            "hostPath",
                                            root.hostPath().toString(),
                                            "mountedPath",
                                            "/" + (name == null ? "" : name.toString()),
                                            "operational",
                                            root.hostPath().equals(operational));
                                })
                        .toList());
    }

    public static @NonNull List<Map<String, Object>> tools(@NonNull List<ToolDefinition> tools) {
        return tools.stream()
                .sorted(Comparator.comparing(ToolDefinition::name))
                .map(
                        tool -> {
                            Map<String, Object> value = new LinkedHashMap<>();
                            value.put("name", tool.name());
                            value.put("description", tool.description());
                            value.put("arguments", arguments(tool.inputSchema(), ""));
                            value.put("examples", tool.examples());
                            value.put("returnExamples", tool.returnExamples());
                            value.put(
                                    "formats",
                                    tool.resultFormats().stream()
                                            .map(
                                                    format ->
                                                            Map.of(
                                                                    "id",
                                                                    format.id(),
                                                                    "description",
                                                                    format.description()))
                                            .toList());
                            value.put("documentation", tool.documentation());
                            return value;
                        })
                .toList();
    }

    private static @NonNull List<Map<String, Object>> arguments(
            @NonNull Map<?, ?> schema, @NonNull String prefix) {
        List<Map<String, Object>> result = new ArrayList<>();
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
                            Map.of(
                                    "name",
                                    name,
                                    "type",
                                    type,
                                    "itemType",
                                    itemType,
                                    "required",
                                    required.contains(key),
                                    "description",
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
