package top.focess.veto.agent.loop;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import top.focess.veto.agent.identity.SystemPromptResolver;
import top.focess.veto.agent.screening.DeployerPolicy;
import top.focess.veto.agent.tool.AgentToolDefinition;
import top.focess.veto.agent.tool.ToolSchemaCompiler;
import top.focess.veto.agent.translation.VetoCapabilityTranslator;
import top.focess.veto.agent.workspace.PathMode;
import top.focess.veto.agent.workspace.TrustMarker;
import top.focess.veto.agent.workspace.Workspace;
import top.focess.veto.agent.workspace.WorkspaceRoot;
import top.focess.veto.api.agent.tool.ToolCapability;
import top.focess.veto.api.agent.tool.ToolDocs;
import top.focess.veto.api.agent.tool.ToolDocumentation;
import top.focess.veto.api.llm.ToolDefinition;
import top.focess.veto.api.llm.ToolResultPresentationMode;
import top.focess.veto.builtin.planning.SubmitPlanTool;
import top.focess.veto.builtin.response.AnswerWithCitationsTool;
import top.focess.veto.builtin.tools.AskUserTool;

class PromptCapabilityContractTest {
    @Test
    void planSchemaAndInstructionsExposeCitationsOnlyForAnAvailableAnswerCapability(
            @TempDir @NonNull Path root) {
        var plan =
                AgentToolDefinition.from(
                        "submit_plan",
                        ToolDocs.nonNullClass(SubmitPlanTool.class),
                        ToolDocs.nonNullClass(SubmitPlanTool.Args.class),
                        ToolCapability.LOOP_CONTROL);
        var answer =
                AgentToolDefinition.from(
                        "answer_with_citations",
                        ToolDocs.nonNullClass(AnswerWithCitationsTool.class),
                        ToolDocs.nonNullClass(AnswerWithCitationsTool.Args.class),
                        ToolCapability.LOOP_CONTROL);
        for (boolean citationsAvailable : List.of(false, true)) {
            List<top.focess.veto.agent.tool.ToolDefinition> manifest =
                    citationsAvailable ? List.of(plan, answer) : List.of(plan);
            var persona = new AgentPersona("fixture", "Fixture", "", Set.copyOf(manifest));
            var flat = new VetoCapabilityTranslator().translateTools(manifest);
            var planSchema =
                    new ObjectMapper()
                            .valueToTree(
                                    flat.stream()
                                            .filter(tool -> tool.name().equals("submit_plan"))
                                            .findFirst()
                                            .orElseThrow()
                                            .inputSchema());
            var modes =
                    planSchema.at(
                            "/properties/actions/items/anyOf/0/properties/response_mode/enum");
            assertEquals(citationsAvailable ? 2 : 1, modes.size());
            assertEquals("TEXT", modes.get(0).asText());
            if (citationsAvailable) assertEquals("CITATIONS", modes.get(1).asText());
            var compiler =
                    new PromptCompiler(
                            new VetoCapabilityTranslator(),
                            new SystemPromptResolver(),
                            new ObjectMapper(),
                            "FULL_ACCESS");
            var linked =
                    compiler.linkSystemSource(
                            persona,
                            Workspace.single(root, PathMode.REAL),
                            null,
                            ToolResultPresentationMode.BASIC);
            String prompt = linked.text();
            assertTrue(
                    linked.sources().stream()
                            .anyMatch(span -> span.source().equals("plan-system-prompt.mdc")));
            assertTrue(prompt.contains("## Plan execution"));
            assertTrue(prompt.contains("schema offers `CITATIONS`"));
            assertFalse(prompt.contains("@if"));
        }
    }

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
            @NonNull Role role, @NonNull PathMode pathMode, boolean capabilities) {
        var persona = new AgentPersona("fixture", "Fixture agent", "", Set.of(), role);
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
                        ToolResultPresentationMode.BASIC);
        // Keep this fixture independent of any local VETO.md in the developer checkout.
        data.put("lawSources", List.of());
        return data;
    }

    @Test
    void capabilityInstructionsFollowActualManifestAcrossRoles() {
        for (Role role : List.of(Role.STANDALONE, Role.LEADER, Role.MATE)) {
            for (boolean capabilities : List.of(false, true)) {
                String prompt =
                        PromptLibrary.text(
                                "default-system-prompt", inputs(role, PathMode.REAL, capabilities));
                // Tool names alone do not install plugin instructions.
                assertFalse(prompt.contains("## Plan execution"));
                assertFalse(prompt.contains("For clickable references"));
                assertFalse(prompt.contains("## Available skills"));
                assertFalse(prompt.contains("fixture-skill"));
                assertFalse(prompt.contains("@if"));
            }
        }
    }

    @Test
    void windowsVirtualWorkspaceUsesMountPathsWhileRetainingHostFacts() {
        var data = inputs(Role.STANDALONE, PathMode.VIRTUAL, false);
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
        var data = inputs(Role.STANDALONE, PathMode.REAL, false);
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

        assertTrue(prompt.contains("This tier excludes the separately labeled Workspace law"));
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
        var persona = new AgentPersona("fixture", "Fixture", "", Set.of(), Role.STANDALONE);
        var data =
                PromptInputs.standard(
                        persona,
                        workspace,
                        null,
                        List.of(),
                        DeployerPolicy.FULL_ACCESS,
                        ToolResultPresentationMode.BASIC);
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
