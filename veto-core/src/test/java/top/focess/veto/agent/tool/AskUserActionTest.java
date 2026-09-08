package top.focess.veto.agent.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import top.focess.veto.agent.capability.UserInteractionCapabilityImpl;
import top.focess.veto.agent.intercept.ToolExecutionPermit;
import top.focess.veto.agent.tool.builtin.AskUserTool;
import top.focess.veto.agent.tool.builtin.UserQuestionRegistry;
import top.focess.veto.controller.UserQuestionController;
import top.focess.veto.llm.core.ToolCall;
import top.focess.veto.llm.core.ToolResultPresentationMode;
import top.focess.veto.session.SessionService;
import top.focess.veto.util.Nullness;
import top.focess.veto.vault.KeysteadVault;

/** Exercises the real tool dispatch, pending registry, and HTTP response path together. */
@Timeout(15)
class AskUserActionTest {
    @ParameterizedTest
    @EnumSource(ToolResultPresentationMode.class)
    void tenQuestionActionReturnsHttpAnswers(@NonNull ToolResultPresentationMode mode)
            throws Exception {
        executeAction(mode, false);
    }

    @ParameterizedTest
    @EnumSource(ToolResultPresentationMode.class)
    void tenQuestionActionReturnsHttpCancellation(@NonNull ToolResultPresentationMode mode)
            throws Exception {
        executeAction(mode, true);
    }

    private void executeAction(@NonNull ToolResultPresentationMode mode, boolean cancel)
            throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        UserQuestionRegistry registry = new UserQuestionRegistry();
        AskUserTool tool = new AskUserTool(new UserInteractionCapabilityImpl(registry));
        ApplicationContext spring = mock(ToolDocs.nonNullClass(ApplicationContext.class));
        when(spring.getBeansOfType(AgentTool.class)).thenReturn(Map.of("askUserTool", tool));
        ToolEngineImpl engine = new ToolEngineImpl(mapper, List.of(), spring);
        engine.init();
        ToolDefinition definition = Nullness.requireNonNull(engine.resolveDefinition("ask_user"));

        SessionService sessions = mock(ToolDocs.nonNullClass(SessionService.class));
        KeysteadVault vault = mock(ToolDocs.nonNullClass(KeysteadVault.class));
        when(vault.currentUser()).thenReturn("alice");
        when(sessions.primaryAgentIdFor("session", "alice")).thenReturn(Optional.of("agent"));
        var mvc =
                MockMvcBuilders.standaloneSetup(
                                new UserQuestionController(sessions, registry, vault))
                        .build();

        // Raw JSON deliberately enters before schema validation and record deserialization.
        StringJoiner questions = new StringJoiner(",", "{\"questions\":[", "]}");
        for (int i = 0; i < 10; i++) {
            questions.add(
                    """
                    {"header":"Choice","id":"question_%d","question":"Which option?",
                    "options":[{"label":"First (Recommended)","description":"Default."},
                    {"label":"Second","description":"Alternative."}]}
                    """
                            .formatted(i));
        }
        Map<String, Object> arguments =
                mapper.readValue(questions.toString(), new TypeReference<Map<String, Object>>() {});
        ToolCall call = new ToolCall("ask_user", arguments, "action-call");
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        var empty = ToolExecutionPermit.empty();
        ToolExecutionPermit permit =
                new ToolExecutionPermit(
                                call,
                                ToolCapability.USER_INTERACTION,
                                null,
                                null,
                                Map.of(),
                                List.of(),
                                null,
                                empty.deployerPolicy(),
                                empty.protectedPaths(),
                                null)
                        .withCaller("agent", userId, null, "alice", sessionId);

        var executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            var result =
                    executor.submit(
                            () -> {
                                ToolCallContextHolder.set(
                                        new ToolCallContext(
                                                "agent", userId, null, "alice", sessionId, mode,
                                                false, permit));
                                try {
                                    return engine.execute(call, definition);
                                } finally {
                                    ToolCallContextHolder.clear();
                                }
                            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (registry.pendingFor("agent").isEmpty()
                    && !result.isDone()
                    && System.nanoTime() < deadline) Thread.sleep(5);
            assertEquals(
                    1,
                    registry.pendingFor("agent").size(),
                    "Tool engine must publish a batch before HTTP submission");
            mvc.perform(get("/api/sessions/session/questions"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].callId").value("action-call"))
                    .andExpect(jsonPath("$[0].questions.length()").value(10));
            Map<String, String> answers = new LinkedHashMap<>();
            if (cancel) {
                mvc.perform(post("/api/sessions/session/questions/action-call/cancel"))
                        .andExpect(status().isNoContent());
            } else {
                for (int i = 9; i >= 0; i--)
                    answers.put("question_" + i, i == 9 ? "Custom answer" : "First (Recommended)");
                mvc.perform(
                                post("/api/sessions/session/questions/action-call")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                mapper.writeValueAsString(
                                                        Map.of("answers", answers))))
                        .andExpect(status().isNoContent());
            }
            ToolResult completed = result.get(3, TimeUnit.SECONDS);
            assertEquals("ask_user", completed.toolName());
            assertEquals("action-call", completed.callId());
            if (cancel) {
                assertEquals(ToolResultStatus.CANCELLED, completed.status());
                assertEquals(ToolResultFormat.PLAINTEXT, completed.format());
                assertEquals("USER_CANCELLED", completed.errorCode());
            } else {
                assertTrue(completed.success(), completed.content());
                assertEquals(ToolResultFormat.JSON, completed.format());
                assertEquals(
                        mapper.valueToTree(answers),
                        mapper.readTree(completed.content()).path("answers"));
            }
            mvc.perform(get("/api/sessions/session/questions"))
                    .andExpect(jsonPath("$.length()").value(0));
        } finally {
            executor.shutdownNow();
        }
    }
}
