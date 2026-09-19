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
import top.focess.veto.agent.tool.ResponseSubmission;
import top.focess.veto.agent.tool.ToolDocumentation;
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
        data.put("guided", guided);
        data.put(
                "planCitations",
                persona.whitelistedTools().stream()
                        .anyMatch(
                                tool ->
                                        ResponseSubmission.Metadata.kindOf(tool)
                                                        == ResponseSubmission.Kind.ANSWER
                                                && tools.stream()
                                                        .anyMatch(
                                                                available ->
                                                                        available
                                                                                .name()
                                                                                .equals(
                                                                                        tool
                                                                                                .name()))));
        data.put("tools", tools(tools));
        data.put("toolNames", tools.stream().map(ToolDefinition::name).toList());
        data.put(
                "skills",
                persona.registeredSkills().stream()
                        .map(skill -> new Skill(skill.name(), skill.description()))
                        .toList());
        return data;
    }

    private static @NonNull String displayedRoot(@NonNull Path root, @NonNull PathMode mode) {
        if (mode != PathMode.VIRTUAL) return root.toString();
        Path name = root.getFileName();
        return "/" + (name == null ? "" : name.toString());
    }

    public record Environment(@NonNull String os, @NonNull String arch, boolean windows) {}

    public record Root(
            @NonNull String hostPath, @NonNull String mountedPath, boolean operational) {}

    public record WorkspaceInput(@NonNull String pathMode, @NonNull List<Root> roots) {}

    public record ToolInput(
            @NonNull String name,
            @NonNull String description,
            @NonNull List<String> examples,
            @NonNull List<String> returnExamples,
            @NonNull List<Format> formats,
            @NonNull ToolDocumentation documentation) {}

    public record Format(@NonNull String id, @NonNull String description) {}

    public record Persona(
            @NonNull String name, @NonNull String description, @NonNull String role) {}

    public record Skill(@NonNull String name, @NonNull String description) {}

    public record LawSource(
            @NonNull String root,
            @NonNull String file,
            boolean override,
            @NonNull String content) {}

    public static @NonNull Environment environment() {
        String os = System.getProperty("os.name", "unknown");
        return new Environment(
                os,
                System.getProperty("os.arch", "unknown"),
                os.toLowerCase(Locale.ROOT).contains("win"));
    }

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

    public static @NonNull List<ToolInput> tools(@NonNull List<ToolDefinition> tools) {
        return tools.stream()
                .sorted(Comparator.comparing(ToolDefinition::name))
                .map(
                        tool ->
                                new ToolInput(
                                        tool.name(),
                                        tool.description(),
                                        tool.examples(),
                                        tool.returnExamples(),
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
}
