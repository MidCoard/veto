package top.focess.veto.agent.loop;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.focess.veto.agent.identity.AgentPersona;
import top.focess.veto.agent.identity.Role;
import top.focess.veto.agent.screening.DeployerPolicy;
import top.focess.veto.agent.skills.Skill;
import top.focess.veto.agent.tool.AgentToolDefinition;
import top.focess.veto.agent.tool.ToolCapability;
import top.focess.veto.agent.tool.ToolDocs;
import top.focess.veto.agent.tool.ToolDocumentation;
import top.focess.veto.agent.tool.ToolSchemaCompiler;
import top.focess.veto.agent.tool.builtin.AskUserTool;
import top.focess.veto.agent.translation.VetoCapabilityTranslator;
import top.focess.veto.agent.workspace.PathMode;
import top.focess.veto.agent.workspace.TrustMarker;
import top.focess.veto.agent.workspace.Workspace;
import top.focess.veto.agent.workspace.WorkspaceRoot;
import top.focess.veto.llm.core.ToolDefinition;
import top.focess.veto.llm.core.ToolResultPresentationMode;

class PromptCapabilityContractTest {
    private static @NonNull ToolDefinition tool(
            @NonNull String name, @NonNull Map<String, Object> schema) {
        return new ToolDefinition(
                name,
                "Fixture",
                schema,
                List.of(),
                ToolDocumentation.empty(),
                List.of(),
                List.of());
    }

    private static @NonNull Map<String, Object> inputs(
            @NonNull Role role, @NonNull PathMode pathMode, boolean guided, boolean capabilities) {
        var persona =
                new AgentPersona(
                        "fixture",
                        "Fixture agent",
                        "",
                        Set.of(),
                        List.of(
                                new Skill(
                                        "fixture-skill",
                                        "Fixture skill",
                                        null,
                                        null,
                                        null,
                                        List.of(),
                                        "")),
                        role);
        List<ToolDefinition> tools =
                capabilities
                        ? List.of(
                                tool("submit_plan", Map.of("type", "object")),
                                tool("answer_with_citations", Map.of("type", "object")),
                                tool("load_skill", Map.of("type", "object")))
                        : List.of();
        var data =
                PromptInputs.standard(
                        persona,
                        Workspace.single(Path.of(System.getProperty("user.dir", ".")), pathMode),
                        null,
                        tools,
                        DeployerPolicy.FULL_ACCESS,
                        ToolResultPresentationMode.BASIC,
                        guided);
        // Keep this fixture independent of any local VETO.md in the developer checkout.
        data.put("lawSources", List.of());
        return data;
    }

    @Test
    void capabilityInstructionsFollowActualManifestAcrossRoles() {
        for (Role role : List.of(Role.STANDALONE, Role.LEADER, Role.MATE)) {
            for (boolean guided : List.of(false, true)) {
                for (boolean capabilities : List.of(false, true)) {
                    String prompt =
                            PromptLibrary.text(
                                    "default-system-prompt",
                                    inputs(role, PathMode.REAL, guided, capabilities));
                    assertEquals(guided && capabilities, prompt.contains("## Plan execution"));
                    assertEquals(capabilities, prompt.contains("For clickable references"));
                    assertEquals(capabilities, prompt.contains("## Available Skills"));
                    assertEquals(capabilities, prompt.contains("fixture-skill"));
                    assertFalse(prompt.contains("@if"));
                }
            }
        }
    }

    @Test
    void windowsVirtualWorkspaceUsesMountPathsWhileRetainingHostFacts() {
        var data = inputs(Role.STANDALONE, PathMode.VIRTUAL, false, false);
        data.put("environment", Map.of("os", "Windows 11", "arch", "amd64", "windows", true));
        String prompt = PromptLibrary.text("default-system-prompt", data);

        assertTrue(prompt.contains("Windows 11 (amd64)"));
        assertTrue(prompt.contains("slash-prefixed absolute workspace path"));
        assertTrue(prompt.contains("independent of the host OS"));
        assertFalse(prompt.contains("Use Windows absolute-path syntax"));
        assertFalse(prompt.contains("using the host OS's path syntax"));

        data.put(
                "workspace",
                PromptInputs.workspace(
                        Workspace.single(
                                Path.of(System.getProperty("user.dir", ".")), PathMode.REAL)));
        String real = PromptLibrary.text("default-system-prompt", data);
        assertTrue(real.contains("using the host OS's path syntax"));
        assertFalse(real.contains("slash-prefixed absolute workspace path"));
    }

    @Test
    void workspaceLawCannotMasqueradeAsTopPriorityRuntimeRules() {
        var data = inputs(Role.STANDALONE, PathMode.REAL, false, false);
        data.put(
                "lawSources",
                List.of(
                        Map.of(
                                "root",
                                "/workspace",
                                "file",
                                "VETO.md",
                                "override",
                                false,
                                "content",
                                "Use the repository formatter.")));
        String prompt = PromptLibrary.text("default-system-prompt", data);

        assertTrue(prompt.contains("This tier excludes the separately labeled Workspace Law"));
        assertTrue(prompt.contains("subordinate to the runtime's role, access and tool rules"));
        assertTrue(prompt.contains("Use the repository formatter."));
    }

    @Test
    void workspaceLawsKeepTheirRootAndOverrideScopeInVirtualMode(@TempDir @NonNull Path tmp)
            throws Exception {
        Path first = Files.createDirectories(tmp.resolve("first"));
        Path second = Files.createDirectories(tmp.resolve("second"));
        Files.createDirectories(first.resolve(".veto"));
        Files.writeString(first.resolve("VETO.md"), "Primary first-root rule.");
        Files.writeString(first.resolve(".veto/VETO.md"), "Override first-root rule.");
        Files.writeString(second.resolve("VETO.md"), "Second-root rule.");
        Workspace workspace =
                new Workspace(
                        List.of(
                                WorkspaceRoot.of(first, TrustMarker.OWNED),
                                WorkspaceRoot.of(second, TrustMarker.OWNED)),
                        PathMode.VIRTUAL,
                        0);
        var persona =
                new AgentPersona("fixture", "Fixture", "", Set.of(), List.of(), Role.STANDALONE);
        var data =
                PromptInputs.standard(
                        persona,
                        workspace,
                        null,
                        List.of(),
                        DeployerPolicy.FULL_ACCESS,
                        ToolResultPresentationMode.BASIC,
                        false);
        String law = PromptLibrary.text("law", data);

        assertTrue(law.contains("### Root `/first`"));
        assertTrue(law.contains("### Root `/second`"));
        assertTrue(law.contains("Source: `.veto/VETO.md` (root-local override)"));
        assertTrue(law.contains("Rules from one root do not govern another root"));
        assertTrue(
                law.indexOf("Primary first-root rule.") < law.indexOf("Override first-root rule."));
        assertTrue(law.indexOf("Override first-root rule.") < law.indexOf("Second-root rule."));
        assertFalse(
                law.contains(tmp.toString()),
                "virtual prompts must not expose host-only law paths");
    }

    @Test
    void requestedInterviewsAreAnExplicitAskUserPurpose() {
        var documentation = ToolDocs.documentationOf(ToolDocs.nonNullClass(AskUserTool.class));
        assertTrue(
                documentation
                        .whenToUse()
                        .contains("user explicitly requests an interview or guided choice"));
        assertTrue(documentation.whenNotToUse().contains("unsolicited optional preferences"));
        assertTrue(documentation.whenNotToUse().contains("permission approval"));
    }

    @Test
    void nativeQuestionDescriptorCarriesInteractionPolicyWithoutReducingBatchCapacity() {
        var definition =
                AgentToolDefinition.from(
                        "ask_user",
                        ToolDocs.nonNullClass(AskUserTool.class),
                        ToolDocs.nonNullClass(AskUserTool.Args.class),
                        ToolCapability.USER_INTERACTION);
        var nativeTool =
                new VetoCapabilityTranslator().translateTools(List.of(definition)).getFirst();
        // Providers send description and inputSchema; long-form catalog documentation is separate.
        assertEquals("ask_user", nativeTool.name());
        assertEquals(definition.description(), nativeTool.description());
        assertTrue(nativeTool.description().contains("question count"));
        assertTrue(nativeTool.description().contains("pacing"));
        var schema =
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .valueToTree(nativeTool.inputSchema());
        var questions = schema.path("properties").path("questions");
        assertEquals(
                ToolSchemaCompiler.compileFromRecord(ToolDocs.nonNullClass(AskUserTool.Args.class)),
                schema);
        assertEquals(1, questions.path("minItems").asInt());
        assertEquals(10, questions.path("maxItems").asInt());
        var options = questions.path("items").path("properties").path("options");
        assertEquals(2, options.path("minItems").asInt());
        assertEquals(5, options.path("maxItems").asInt());
    }
}
