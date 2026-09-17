package top.focess.veto.agent.loop;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.identity.AgentPersona;
import top.focess.veto.agent.screening.DeployerPolicy;
import top.focess.veto.agent.workspace.PathMode;
import top.focess.veto.agent.workspace.VetoMdResolver;
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
                                        Map.of(
                                                "root",
                                                        displayedRoot(
                                                                source.root(),
                                                                workspace.pathMode()),
                                                "file", source.relativePath(),
                                                "override", source.override(),
                                                "content", source.content()))
                        .toList());
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

    private static @NonNull String displayedRoot(@NonNull Path root, @NonNull PathMode mode) {
        if (mode != PathMode.VIRTUAL) return root.toString();
        Path name = root.getFileName();
        return "/" + (name == null ? "" : name.toString());
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
}
