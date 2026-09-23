package top.focess.veto.agent.loop;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import top.focess.veto.agent.identity.AgentPersona;
import top.focess.veto.agent.identity.SystemPromptResolver;
import top.focess.veto.agent.tool.RemoteToolDefinition;
import top.focess.veto.agent.translation.VetoCapabilityTranslator;
import top.focess.veto.agent.workspace.PathMode;
import top.focess.veto.agent.workspace.Workspace;
import top.focess.veto.api.llm.ChatMessage;
import top.focess.veto.api.llm.LlmOptions;
import top.focess.veto.api.llm.ProviderType;
import top.focess.veto.api.llm.ResponseContract;
import top.focess.veto.api.llm.ToolDefinition;
import top.focess.veto.api.llm.ToolResultPresentationMode;
import top.focess.veto.api.llm.VetoRequest;
import top.focess.veto.llm.core.*;

class ScopedPromptTest {
    @Test
    void generationAndPredicatePromptsUseCurrentToolsWithoutRewritingConversation() {
        var mapper = new ObjectMapper();
        var translator = new VetoCapabilityTranslator();
        var compiler =
                new PromptCompiler(translator, new SystemPromptResolver(), mapper, "PROTECTED");
        var schema = mapper.createObjectNode().put("type", "object");
        var read = new RemoteToolDefinition("view_file", "Read a file", "fixture", schema);
        var cite =
                new RemoteToolDefinition(
                        "answer_with_citations", "Submit a cited answer", "fixture", schema);
        var persona = new AgentPersona("test", "Veto", "Review", Set.of(read, cite), List.of());
        var workspace =
                Workspace.single(Path.of(System.getProperty("user.dir", ".")), PathMode.REAL);
        String full =
                compiler.linkSystemMessage(
                        persona, workspace, null, ToolResultPresentationMode.BASIC);
        var conversation =
                List.of(
                        ChatMessage.user("Review this source.").withSourceTurns(List.of(2)),
                        ChatMessage.assistantToolCall("read-call", "view_file", "{}", "", null)
                                .withSourceTurns(List.of(3)),
                        ChatMessage.toolResult("read-call", "Source evidence")
                                .withSourceTurns(List.of(4)));
        for (int channel = 0; channel < 3; channel++) {
            boolean generation = channel < 2;
            boolean citations = channel == 1;
            var contract =
                    generation ? ResponseContract.generation() : ResponseContract.predicate();
            var tools =
                    citations
                            ? translator.translateTools(List.of(cite))
                            : List.<ToolDefinition>of();
            var messages = new java.util.ArrayList<ChatMessage>();
            messages.add(ChatMessage.system(full));
            messages.addAll(conversation);
            var request =
                    new VetoRequest(
                            full,
                            "Review",
                            tools,
                            ProviderType.ANTHROPIC,
                            "test",
                            "reference",
                            LlmOptions.defaults(),
                            messages,
                            null,
                            null,
                            citations,
                            contract);
            var scoped =
                    compiler.scopeRequest(
                            request, persona, workspace, null, ToolResultPresentationMode.BASIC);
            assertFalse(scoped.systemPrompt().contains("### `view_file`"));
            assertFalse(scoped.systemPrompt().contains("## Plan execution"));
            assertEquals(citations, scoped.systemPrompt().contains("### `answer_with_citations`"));
            assertEquals(
                    citations,
                    scoped.systemPrompt().contains("registered `answer_with_citations` tool"));
            assertEquals(conversation, scoped.messages().subList(1, scoped.messages().size()));
            assertEquals(scoped.systemPrompt(), scoped.messages().getFirst().content());
            assertFalse(scoped.messages().getFirst().promptSources().isEmpty());
            assertEquals(contract, scoped.responseContract());
            assertEquals(tools, scoped.tools());
            assertTrue(request.systemPrompt().contains("### `view_file`"));
        }
    }
}
