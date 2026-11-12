package top.focess.veto.secret.training;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.TurnRecord;
import top.focess.veto.agent.TurnType;
import top.focess.veto.agent.identity.AgentPersona;
import top.focess.veto.agent.identity.Role;
import top.focess.veto.agent.identity.SystemPromptResolver;
import top.focess.veto.agent.loop.ContextBudgetConfiguration;
import top.focess.veto.agent.loop.PromptCompiler;
import top.focess.veto.agent.loop.PromptLibrary;
import top.focess.veto.agent.tool.NativeToolDefinition;
import top.focess.veto.agent.translation.VetoCapabilityTranslator;
import top.focess.veto.agent.workspace.PathMode;
import top.focess.veto.agent.workspace.Workspace;
import top.focess.veto.api.agent.tool.ToolDocs;
import top.focess.veto.api.agent.tool.ToolSecurity;
import top.focess.veto.api.llm.ToolResultPresentationMode;
import top.focess.veto.api.plugin.contract.JsonValues;
import top.focess.veto.llm.core.ToolResultPresenter;
import top.focess.veto.secret.ImportCredentialTool;
import top.focess.veto.secret.SecretAgent;

/** Exports native tool-call training targets using the actual Agent prompt and tool schema. */
public final class SecretSftTool {
    private SecretSftTool() {}

    /**
     * Input annotations contain text, source_kind and explicit imports; existing outputs are
     * refused.
     */
    public static void main(String @NonNull [] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("input output");
        var source = Path.of(args[0]);
        var output = Path.of(args[1]);
        if (Files.exists(output)) throw new IllegalArgumentException("Output already exists");
        if (Files.size(source) > 32_000_000)
            throw new IllegalArgumentException("Input exceeds 32 MB");
        var mapper = new ObjectMapper();
        var compiler =
                new PromptCompiler(
                        new VetoCapabilityTranslator(),
                        new SystemPromptResolver(),
                        mapper,
                        new ToolResultPresenter(mapper),
                        "PROTECTED",
                        new ContextBudgetConfiguration(),
                        16384,
                        1.0);
        var security = ImportCredentialTool.class.getAnnotation(ToolSecurity.class);
        if (security == null) throw new IllegalStateException("Import tool policy is missing");
        var name = SecretAgent.profile(true).tools().iterator().next();
        var tool =
                new NativeToolDefinition(
                        name,
                        ToolDocs.descriptionOf(ImportCredentialTool.class),
                        security.capability(),
                        security.defaultDanger(),
                        security.requiresSemanticScreening(),
                        ImportCredentialTool.class,
                        ImportCredentialTool.Args.class,
                        Map.of());
        var workspace =
                Workspace.single(
                        Path.of("work/tmp/secret-corpus-10k").toAbsolutePath(), PathMode.VIRTUAL);
        var persona =
                new AgentPersona(
                        "secret-export",
                        "secret-import",
                        "Import credentials from incoming content",
                        Set.of(tool),
                        Role.valueOf("SECRET"));
        var parent = output.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        long bytes = 0;
        try (var reader = Files.newBufferedReader(source);
                var writer = Files.newBufferedWriter(output, StandardOpenOption.CREATE_NEW)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                var row = Objects.requireNonNull(mapper.readTree(line));
                if (!row.path("synthetic").asBoolean() || !row.path("imports").isArray())
                    throw new IllegalArgumentException(
                            "Explicit synthetic import annotations required");
                var text = row.path("text").asText();
                var sourceKind = row.path("source_kind").asText();
                if (!sourceKind.equals("user") && !sourceKind.equals("tool"))
                    throw new IllegalArgumentException("Unknown source_kind");
                boolean userInput = sourceKind.equals("user");
                var profile = SecretAgent.profile(userInput);
                var reference = profile.prompt();
                if (reference == null)
                    throw new IllegalStateException("Missing Secret Agent prompt");
                var guidance =
                        PromptLibrary.compileTool(
                                        SecretAgent.class,
                                        reference.resource(),
                                        JsonValues.toMap(reference.data()))
                                .text();
                var system =
                        compiler.linkSystemSource(
                                persona, workspace, guidance, ToolResultPresentationMode.BASIC);
                if (system.text().isBlank())
                    throw new IllegalStateException("Missing Agent system prompt");
                var compiled =
                        compiler.compile(
                                persona,
                                workspace,
                                guidance,
                                List.of(
                                        TurnRecord.agentInit(
                                                0,
                                                "secret",
                                                system.text(),
                                                "LOCAL",
                                                "top.focess.secret-protection"),
                                        new TurnRecord(
                                                1,
                                                TurnType.USER_PROMPT,
                                                Map.of("content", SecretAgent.sourceInput(text)),
                                                null)),
                                1.0,
                                ToolResultPresentationMode.BASIC,
                                12288L);
                var result = mapper.createObjectNode();
                result.set("id", row.path("id"));
                var tools = result.putArray("tools");
                for (var definition : compiled.tools()) {
                    var function = tools.addObject().put("type", "function").putObject("function");
                    function.put("name", definition.name());
                    function.put("description", definition.description());
                    function.set("parameters", mapper.valueToTree(definition.inputSchema()));
                }
                var messages = result.putArray("messages");
                for (var message : compiled.messages())
                    messages.addObject()
                            .put("role", message.role())
                            .put("content", message.content());
                if (!row.path("imports").isEmpty()) {
                    var calls =
                            messages.addObject()
                                    .put("role", "assistant")
                                    .put("content", "")
                                    .putArray("tool_calls");
                    int count = 0;
                    for (JsonNode item : row.path("imports")) {
                        var value = item.path("value").asText();
                        var visibility = item.path("visibility").asText();
                        if (value.isEmpty()
                                || !text.contains(value)
                                || !(visibility.equals("PRIVATE") || visibility.equals("PUBLIC"))
                                || (!userInput && visibility.equals("PUBLIC")))
                            throw new IllegalArgumentException(
                                    "Invalid exact-value or disclosure annotation");
                        var call =
                                calls.addObject()
                                        .put("id", "import_" + count++)
                                        .put("type", "function");
                        var function = call.putObject("function");
                        function.put("name", name);
                        function.set("arguments", item);
                    }
                } else {
                    messages.addObject().put("role", "assistant").put("content", "");
                }
                var encoded = mapper.writeValueAsString(result);
                bytes += encoded.getBytes(StandardCharsets.UTF_8).length + 1;
                if (bytes > 384_000_000) throw new IllegalStateException("Export exceeds 384 MB");
                writer.write(encoded);
                writer.newLine();
            }
        }
    }
}
