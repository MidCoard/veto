package top.focess.veto.agent.loop;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.identity.Role;
import top.focess.veto.agent.screening.DeployerPolicy;
import top.focess.veto.agent.workspace.PathMode;
import top.focess.veto.agent.workspace.Workspace;
import top.focess.veto.api.llm.ToolDefinition;
import top.focess.veto.api.llm.ToolResultPresentationMode;
import top.focess.veto.api.skills.Skill;

/** Fragment fixtures exercise production MDC sources, without a second renderer. */
final class PromptBlocks {
    private PromptBlocks() {}

    static @NonNull String law(String law) {
        return PromptLibrary.text(
                "law",
                Map.of(
                        "lawSources",
                        law == null || law.isBlank()
                                ? List.of()
                                : List.of(
                                        Map.of(
                                                "root",
                                                "/workspace",
                                                "file",
                                                "VETO.md",
                                                "override",
                                                false,
                                                "content",
                                                law))));
    }

    static @NonNull String identity(String name, String description) {
        return PromptLibrary.text(
                "identity",
                Map.of(
                        "persona",
                        Map.of(
                                "name",
                                name == null ? "" : name,
                                "description",
                                description == null ? "" : description),
                        "guidance",
                        ""));
    }

    static @NonNull String role(Role role) {
        return PromptLibrary.text(
                "role",
                Map.of("persona", Map.of("role", role == null ? "STANDALONE" : role.name())));
    }

    static @NonNull String workspace(Workspace workspace) {
        return PromptLibrary.text(
                "workspace",
                Map.of(
                        "workspace",
                        workspace == null
                                ? Map.of("roots", List.of())
                                : PromptInputs.workspace(workspace)));
    }

    static @NonNull String environment() {
        return PromptLibrary.text("environment", Map.of("environment", PromptInputs.environment()));
    }

    static @NonNull String tools(List<ToolDefinition> tools) {
        return PromptLibrary.text(
                "tool-catalog",
                Map.of("tools", PromptInputs.tools(tools == null ? List.of() : tools)));
    }

    static @NonNull String resultConventions() {
        return resultConventions(ToolResultPresentationMode.BASIC);
    }

    static @NonNull String resultConventions(@NonNull ToolResultPresentationMode mode) {
        return PromptLibrary.text("result-conventions", Map.of("presentation", mode.name()));
    }

    static @NonNull String boundaries(DeployerPolicy policy, @NonNull PathMode mode) {
        return PromptLibrary.text(
                "boundaries",
                Map.of(
                        "policy",
                        policy == null ? "PROTECTED" : policy.name(),
                        "workspace",
                        Map.of("pathMode", mode.name())));
    }

    static @NonNull String skills(List<Skill> skills) {
        return PromptLibrary.text(
                "skills",
                Map.of(
                        "skills",
                        skills == null
                                ? List.of()
                                : skills.stream()
                                        .map(
                                                skill ->
                                                        Map.of(
                                                                "name",
                                                                skill.name(),
                                                                "description",
                                                                skill.description()))
                                        .toList()));
    }
}
