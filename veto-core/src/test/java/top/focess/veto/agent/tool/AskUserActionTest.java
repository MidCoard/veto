package top.focess.veto.agent.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import org.springframework.test.util.ReflectionTestUtils;
import top.focess.veto.agent.intercept.ToolExecutionPermit;
import top.focess.veto.api.agent.tool.ToolCapability;
import top.focess.veto.api.agent.tool.ToolDocs;
import top.focess.veto.api.agent.tool.ToolErrorCode;
import top.focess.veto.api.agent.tool.ToolResult;
import top.focess.veto.api.agent.tool.ToolResultFormat;
import top.focess.veto.api.agent.tool.ToolResultStatus;
import top.focess.veto.api.llm.ToolCall;
import top.focess.veto.api.llm.ToolResultPresentationMode;
import top.focess.veto.integration.plugins.PluginManager;
import top.focess.veto.integration.plugins.QuestionActionFixture;
import top.focess.veto.util.Nullness;
import top.focess.veto.vault.UserContext;

/** Exercises the real tool dispatch, pending registry, and HTTP response path together. */
@Timeout(15)
class AskUserActionTest {
    @ParameterizedTest
    @EnumSource(ToolResultPresentationMode.class)
    void tenQuestionActionReturnsHttpAnswers(@NonNull ToolResultPresentationMode mode)
            throws Exception {
        executeAction(mode, false, false, false);
    }

    @ParameterizedTest
    @EnumSource(ToolResultPresentationMode.class)
    void tenQuestionActionReturnsHttpCancellation(@NonNull ToolResultPresentationMode mode)
            throws Exception {
        executeAction(mode, true, false, false);
    }

    @ParameterizedTest
    @EnumSource(ToolResultPresentationMode.class)
    void pluginStopSettlesActualAdmittedQuestionCall(@NonNull ToolResultPresentationMode mode)
            throws Exception {
        executeAction(mode, true, true, false);
    }

    @ParameterizedTest
    @EnumSource(ToolResultPresentationMode.class)
    void pluginFailureSettlesActualAdmittedQuestionCall(@NonNull ToolResultPresentationMode mode)
            throws Exception {
        executeAction(mode, true, true, true);
    }

    private void executeAction(
            @NonNull ToolResultPresentationMode mode, boolean cancel, boolean stop, boolean fail)
            throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        try (var fixture = new QuestionActionFixture()) {
            var registry = fixture.runtime;
            ApplicationContext spring = mock(ToolDocs.nonNullClass(ApplicationContext.class));
            when(spring.getBeansOfType(PluginManager.class))
                    .thenReturn(Map.of("plugins", fixture.manager));
            ToolEngineImpl engine = new ToolEngineImpl(mapper, List.of(), spring);
            engine.init();
            ToolDefinition definition =
                    Nullness.requireNonNull(engine.resolveDefinition("ask_user"));
            var mvc = fixture.mvc;
            UserContext.set("alice");

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
                    mapper.readValue(
                            questions.toString(), new TypeReference<Map<String, Object>>() {});
            ToolCall call = new ToolCall("ask_user", arguments, "action-call");
            UUID userId = UUID.randomUUID();
            UUID sessionId = UUID.fromString(fixture.session.getId());
            var empty = ToolExecutionPermit.empty();
            ToolExecutionPermit permit =
                    new ToolExecutionPermit(
                                    call,
                                    ToolCapability.USER_INTERACTION,
                                    fixture.manager.plugin("top.focess.builtin").bindingId(),
                                    null,
                                    Map.of(),
                                    List.of(),
                                    null,
                                    empty.deployerPolicy(),
                                    empty.protectedPaths(),
                                    null)
                            .withCaller("agent", userId, "alice", sessionId);

            var executor = Executors.newVirtualThreadPerTaskExecutor();
            try {
                var result =
                        executor.submit(
                                () -> {
                                    ToolCallContextHolder.set(
                                            new ToolCallContext(
                                                    "agent", userId, "alice", sessionId, mode,
                                                    permit));
                                    try {
                                        return engine.execute(call, definition);
                                    } finally {
                                        ToolCallContextHolder.clear();
                                    }
                                });
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (registry.pendingFor(fixture.scope).isEmpty()
                        && !result.isDone()
                        && System.nanoTime() < deadline) Thread.sleep(5);
                assertEquals(
                        1,
                        registry.pendingFor(fixture.scope).size(),
                        "Tool engine must publish a batch before HTTP submission");
                mvc.perform(
                                post("/api/sessions/session/plugin-frontend/actions")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                mapper.writeValueAsString(
                                                        fixture.action("list", Map.of()))))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.items[0].callId").value("action-call"))
                        .andExpect(jsonPath("$.items[0].questions.length()").value(10));
                Map<String, String> answers = new LinkedHashMap<>();
                if (stop) {
                    if (fail)
                        ReflectionTestUtils.invokeMethod(
                                fixture.manager.plugin("top.focess.builtin"), "fail");
                    var closed =
                            executor.submit(
                                    () -> fixture.manager.plugin("top.focess.builtin").close());
                    closed.get(3, TimeUnit.SECONDS);
                } else if (cancel) {
                    mvc.perform(
                                    post("/api/sessions/session/plugin-frontend/actions")
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(
                                                    mapper.writeValueAsString(
                                                            fixture.action(
                                                                    "cancel",
                                                                    Map.of(
                                                                            "callId",
                                                                            "action-call")))))
                            .andExpect(status().isOk());
                } else {
                    for (int i = 9; i >= 0; i--)
                        answers.put(
                                "question_" + i, i == 9 ? "Custom answer" : "First (Recommended)");
                    mvc.perform(
                                    post("/api/sessions/session/plugin-frontend/actions")
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(
                                                    mapper.writeValueAsString(
                                                            fixture.action(
                                                                    "answer",
                                                                    Map.of(
                                                                            "callId",
                                                                            "action-call",
                                                                            "answers",
                                                                            answers)))))
                            .andExpect(status().isOk());
                }
                ToolResult completed = result.get(3, TimeUnit.SECONDS);
                assertEquals("ask_user", completed.toolName());
                assertEquals("action-call", completed.callId());
                if (fail) {
                    assertEquals(ToolResultStatus.FAILURE, completed.status());
                } else if (cancel) {
                    assertEquals(ToolResultStatus.CANCELLED, completed.status());
                    assertEquals(ToolResultFormat.PLAINTEXT, completed.format());
                    assertEquals(ToolErrorCode.LIFECYCLE.USER_CANCELLED, completed.errorCode());
                } else {
                    assertTrue(completed.success(), completed.content());
                    assertEquals(ToolResultFormat.JSON, completed.format());
                    assertEquals(
                            mapper.valueToTree(answers),
                            mapper.readTree(completed.content()).path("answers"));
                }
                assertTrue(registry.pendingFor(fixture.scope).isEmpty());
                if (!stop)
                    mvc.perform(
                                    post("/api/sessions/session/plugin-frontend/actions")
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(
                                                    mapper.writeValueAsString(
                                                            fixture.action("list", Map.of()))))
                            .andExpect(jsonPath("$.items.length()").value(0));
            } finally {
                executor.shutdownNow();
            }
        }
    }
}
