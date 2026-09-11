package top.focess.veto.agent.loop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import top.focess.veto.agent.TurnRecord;
import top.focess.veto.agent.TurnType;
import top.focess.veto.agent.identity.AgentPersona;
import top.focess.veto.agent.identity.Role;
import top.focess.veto.agent.identity.SystemPromptResolver;
import top.focess.veto.agent.tool.ToolResult;
import top.focess.veto.agent.translation.CapabilityTranslator;
import top.focess.veto.agent.translation.VetoCapabilityTranslator;
import top.focess.veto.agent.workspace.PathMode;
import top.focess.veto.agent.workspace.Workspace;
import top.focess.veto.llm.core.*;

class PromptCompilerContextBudgetTest {
    @Test
    void isolatedReaderReservesModelOutputAndKeepsItsConfiguredCeiling() {
        @NonNull CapabilityTranslator translator = mock();
        var compiler = PromptCompiler.isolated(translator, mapper, "Reader rules", 20000);
        var messages =
                List.of(ChatMessage.system("Reader rules"), ChatMessage.user("读取".repeat(400)));
        var schema = mapper.createObjectNode().put("description", "schema ".repeat(200));
        for (ProviderType provider : List.of(ProviderType.ANTHROPIC, ProviderType.DEEPSEEK)) {
            var fits =
                    new VetoRequest(
                            "Reader rules",
                            "Read",
                            List.of(),
                            provider,
                            "reader",
                            "unused",
                            new LlmOptions(null, null, 1000, null, 12000),
                            messages,
                            schema,
                            null);
            assertEquals(messages, compiler.fitRequest(fits).messages());
            var outputHeavy =
                    new VetoRequest(
                            fits.systemPrompt(),
                            fits.userPrompt(),
                            fits.tools(),
                            provider,
                            fits.modelName(),
                            fits.credentialKey(),
                            new LlmOptions(null, null, 11000, null, 12000),
                            messages,
                            schema,
                            null);
            assertThrows(IllegalStateException.class, () -> compiler.fitRequest(outputHeavy));
            assertEquals(messages, outputHeavy.messages());
            var localCeiling = PromptCompiler.isolated(translator, mapper, "Reader rules", 1000);
            assertThrows(IllegalStateException.class, () -> localCeiling.fitRequest(fits));
        }
    }

    @Test
    void recoveryObservationConsumesTheConfiguredInputBudget() {
        var persona = new AgentPersona("test", "test", "test", Set.of(), List.of(), Role.MATE);
        var workspace =
                Workspace.single(Path.of(System.getProperty("user.dir", ".")), PathMode.REAL);
        var history = List.of(TurnRecord.userPrompt(1, "New task"));
        var compiler = compiler(32000);
        assertDoesNotThrow(
                () ->
                        compiler.compile(
                                persona,
                                workspace,
                                null,
                                history,
                                false,
                                1.1,
                                ToolResultPresentationMode.BASIC,
                                10000L,
                                ""));
        assertThrows(
                IllegalStateException.class,
                () ->
                        compiler.compile(
                                persona,
                                workspace,
                                null,
                                history,
                                false,
                                1.1,
                                ToolResultPresentationMode.BASIC,
                                10000L,
                                "observation ".repeat(50000)));
    }

    @Test
    void approvalReceiptsRemainBoundToResultsAcrossCompilationAndRewind() {
        var persona = new AgentPersona("test", "test", "test", Set.of(), List.of(), Role.MATE);
        var workspace =
                Workspace.single(Path.of(System.getProperty("user.dir", ".")), PathMode.REAL);
        for (boolean guided : List.of(false, true)) {
            for (ToolResultPresentationMode mode :
                    List.of(
                            ToolResultPresentationMode.BASIC,
                            ToolResultPresentationMode.DETAILED)) {
                var result = new ToolResult("run_task", "approved-call", true, "raw output");
                String original = new ToolResultPresenter(mapper).present(result, mode);
                var recorded = TurnRecord.presentedToolResponse(3, result, original, mode);
                Map<String, Object> payload = new HashMap<>(recorded.payload());
                payload.put(
                        "approval",
                        Map.of("decision", "ACCEPT_COMMAND", "decisionSource", "CLIENT_RESPONSE"));
                var receipt = new TurnRecord(3, TurnType.TOOL_RESPONSE, payload, null);
                List<TurnRecord> history =
                        List.of(
                                TurnRecord.userPrompt(1, "Run the requested task"),
                                TurnRecord.toolCall(
                                        2, new ToolCall("run_task", Map.of(), "approved-call")),
                                receipt,
                                TurnRecord.userPrompt(4, "A different request"));
                var compiled =
                        compiler(32000)
                                .compile(persona, workspace, null, history, guided, 1.1, mode);
                var messages = compiled.messages();
                var response =
                        messages.stream()
                                .filter(m -> "tool".equals(m.role()))
                                .findFirst()
                                .orElseThrow();
                assertEquals(original, response.content());
                assertEquals("approved-call", response.callId());
                int index = messages.indexOf(response);
                var observation = messages.get(index + 1);
                assertEquals("user", observation.role());
                assertTrue(observation.content().contains("CLIENT_RESPONSE"));
                assertTrue(observation.content().contains("approved-call"));
                assertEquals(List.of(3), observation.sourceTurns());
                assertEquals("A different request", messages.get(index + 2).content());

                var rewound =
                        compiler(32000)
                                .resolveRewinds(
                                        List.of(
                                                history.get(0),
                                                history.get(1),
                                                receipt,
                                                TurnRecord.rewind(4, 0)),
                                        mode);
                assertTrue(
                        rewound.stream()
                                .noneMatch(
                                        m -> m.content().contains("Runtime approval observation")));
                payload.put(
                        "approval",
                        Map.of("decision", "INVENTED", "decisionSource", "CLIENT_RESPONSE"));
                var invalid = new TurnRecord(3, TurnType.TOOL_RESPONSE, payload, null);
                assertEquals(1, compiler(32000).resolveRewinds(List.of(invalid), mode).size());
                assertEquals(1, compiler(32000).resolveRewinds(List.of(recorded), mode).size());
            }
        }
    }

    private final @NonNull ObjectMapper mapper = new ObjectMapper();

    @Test
    void executionFailureRemainsOutOfTheNextProviderConversation() {
        var history =
                List.of(
                        TurnRecord.userPrompt(1, "First request"),
                        new TurnRecord(
                                2,
                                TurnType.EXECUTION_ERROR,
                                Map.of("content", "private failure detail"),
                                null),
                        TurnRecord.userPrompt(3, "Try again"));
        var persona =
                new AgentPersona("test", "test", "test", Set.of(), List.of(), Role.STANDALONE);
        var workspace =
                Workspace.single(Path.of(System.getProperty("user.dir", ".")), PathMode.REAL);
        var compiled = compiler(32000).compile(persona, workspace, null, history, false, 1.1);
        assertFalse(
                compiled.messages().stream()
                        .anyMatch(message -> "private failure detail".equals(message.content())));
    }

    @Test
    void replayedCancellationSeparatesRequestsWithoutExposingFailureDetails() {
        var persona =
                new AgentPersona("test", "test", "test", Set.of(), List.of(), Role.STANDALONE);
        var workspace =
                Workspace.single(Path.of(System.getProperty("user.dir", ".")), PathMode.REAL);
        for (boolean started : List.of(true, false)) {
            Map<String, Object> payload = new HashMap<>();
            payload.put("content", "private provider detail");
            payload.put("outcome", "CANCELLED");
            if (started) payload.put("requestId", "cancelled-request");
            List<TurnRecord> history =
                    List.of(
                            TurnRecord.userPrompt(1, "Old work"),
                            new TurnRecord(2, TurnType.EXECUTION_ERROR, payload, null),
                            TurnRecord.userPrompt(3, "New work"));
            var compiled = compiler(32000).compile(persona, workspace, null, history, false, 1.1);
            assertEquals(
                    started,
                    compiled.messages().stream()
                            .anyMatch(
                                    message ->
                                            message.content().contains("[Runtime cancellation]")));
            assertFalse(
                    compiled.messages().stream()
                            .anyMatch(
                                    message ->
                                            message.content().contains("private provider detail")));
            if (started) {
                var boundary =
                        compiled.messages().stream()
                                .filter(
                                        message ->
                                                message.content()
                                                        .contains("[Runtime cancellation]"))
                                .findFirst()
                                .orElseThrow()
                                .content();
                assertTrue(boundary.contains("At this point in the recorded history"));
                assertTrue(boundary.contains("does not describe any later request"));
            }
        }
    }

    @Test
    void explicitTierWindowReachesSubmissionBudgetWithoutRaisingTheFallbackLimit() {
        String system = "word ".repeat(40000);
        var fallback =
                request(
                        system,
                        List.of(ChatMessage.system(system), ChatMessage.user("Explain TCP")));
        var configured =
                new VetoRequest(
                        system,
                        fallback.userPrompt(),
                        fallback.tools(),
                        fallback.providerType(),
                        fallback.modelName(),
                        fallback.credentialKey(),
                        new LlmOptions(0.2, null, 7000, null, 128000),
                        fallback.messages(),
                        fallback.responseSchema(),
                        fallback.baseUrl());
        var compiler = compiler(32000);
        assertSame(configured, compiler.fitRequest(configured));
        assertThrows(IllegalStateException.class, () -> compiler.fitRequest(fallback));
        var small =
                new VetoRequest(
                        system,
                        configured.userPrompt(),
                        configured.tools(),
                        configured.providerType(),
                        configured.modelName(),
                        configured.credentialKey(),
                        new LlmOptions(0.2, null, 7000, null, 32000),
                        configured.messages(),
                        configured.responseSchema(),
                        configured.baseUrl());
        assertThrows(IllegalStateException.class, () -> compiler.fitRequest(small));
        assertEquals(fallback.messages(), configured.messages());
    }

    @Test
    void compilationKeepsThePriorTopicWithTheConfiguredModelBudget() {
        var compiler = compiler(32000);
        var config = new ContextBudgetConfiguration();
        config.setModelInputTokens(Map.of("DEEPSEEK/test-model", 128000));
        compiler.configureContextBudgets(config);
        var history =
                List.of(
                        TurnRecord.agentInit(
                                1, "standalone", "s".repeat(83423), "DEEPSEEK", "test-model"),
                        TurnRecord.userPrompt(2, "Explain TCP"),
                        TurnRecord.assistantResponse(3, "TCP handshake " + "a".repeat(2124)),
                        TurnRecord.userPrompt(4, "Can you draw a diagram?"));
        var persona =
                new AgentPersona("test", "test", "test", Set.of(), List.of(), Role.STANDALONE);
        var workspace =
                Workspace.single(Path.of(System.getProperty("user.dir", ".")), PathMode.REAL);
        var compiled = compiler.compile(persona, workspace, null, history, false, 1.1);
        assertEquals(0, compiled.trimmedTurns());
        assertEquals(4, compiled.messages().size());
        assertEquals("Explain TCP", compiled.messages().get(1).content());
        assertTrue(compiled.messages().get(2).content().startsWith("TCP handshake"));
        assertThrows(
                IllegalStateException.class,
                () -> compiler(32000).compile(persona, workspace, null, history, false, 1.1));
        assertEquals(4, history.size());
    }

    private @NonNull PromptCompiler compiler(int limit) {
        var compiler =
                new PromptCompiler(
                        new VetoCapabilityTranslator(),
                        new SystemPromptResolver(),
                        mapper,
                        "FULL_ACCESS");
        ReflectionTestUtils.setField(compiler, "maxInputTokens", limit);
        ReflectionTestUtils.setField(compiler, "contextFillRatio", 0.9);
        return compiler;
    }

    private @NonNull VetoRequest request(
            @NonNull String system, @NonNull List<ChatMessage> messages) {
        return new VetoRequest(
                system,
                messages.getLast().content(),
                List.of(),
                ProviderType.DEEPSEEK,
                "test-model",
                "unused",
                LlmOptions.defaults(),
                messages,
                mapper.createObjectNode(),
                null);
    }

    @Test
    void preservesTopicAndAnswerOrRejectsInsteadOfDroppingThem() {
        var compiler = compiler(1000);
        var messages =
                List.of(
                        ChatMessage.system("s".repeat(1700)),
                        ChatMessage.user("Explain TCP"),
                        ChatMessage.assistant("a".repeat(500)),
                        ChatMessage.user("Can you draw a diagram?"));
        var request = request(messages.getFirst().content(), messages);
        assertSame(request, compiler.fitRequest(request, 0.5));
        var error =
                assertThrows(IllegalStateException.class, () -> compiler.fitRequest(request, 1.2));
        assertTrue(
                String.valueOf(error.getMessage()).contains("No conversation history was removed"));
        assertEquals(messages, request.messages());
    }

    @Test
    void systemAloneCannotSilentlyExceedTheBudget() {
        var request = request("x".repeat(4000), List.of(ChatMessage.user("hello")));
        assertThrows(IllegalStateException.class, () -> compiler(1000).fitRequest(request));
    }

    @Test
    void countsResponseSchemaAndToolArguments() {
        var schema = mapper.createObjectNode().put("description", "s".repeat(4000));
        var request =
                new VetoRequest(
                        "system",
                        "continue",
                        List.of(),
                        ProviderType.DEEPSEEK,
                        "test-model",
                        "unused",
                        LlmOptions.defaults(),
                        List.of(ChatMessage.user("continue")),
                        schema,
                        null);
        assertThrows(IllegalStateException.class, () -> compiler(1000).fitRequest(request));
        var toolHistory =
                List.of(
                        ChatMessage.user("read"),
                        ChatMessage.assistantToolCall("c1", "read", "x".repeat(4000), "", null),
                        ChatMessage.toolResult("c1", "done"),
                        ChatMessage.user("continue"));
        assertThrows(
                IllegalStateException.class,
                () -> compiler(1000).fitRequest(request("system", toolHistory)));
    }

    @Test
    void explicitModelBudgetDoesNotRaiseUnknownModelsFallback() {
        var compiler = compiler(1000);
        var config = new ContextBudgetConfiguration();
        config.setModelInputTokens(Map.of("DEEPSEEK/test-model", 8000));
        compiler.configureContextBudgets(config);
        var request = request("x".repeat(4000), List.of(ChatMessage.user("hello")));
        assertSame(request, compiler.fitRequest(request));
        var other =
                new VetoRequest(
                        request.systemPrompt(),
                        request.userPrompt(),
                        request.tools(),
                        ProviderType.OPENAI,
                        request.modelName(),
                        "unused",
                        request.options(),
                        request.messages(),
                        request.responseSchema(),
                        null);
        assertThrows(IllegalStateException.class, () -> compiler.fitRequest(other));
        assertThrows(
                IllegalArgumentException.class,
                () -> config.setModelInputTokens(Map.of("DEEPSEEK/test-model", 0)));
    }
}
