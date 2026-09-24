package top.focess.veto.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import top.focess.veto.builtin.questions.Option;
import top.focess.veto.builtin.questions.Question;
import top.focess.veto.integration.plugins.QuestionActionFixture;
import top.focess.veto.vault.UserContext;

class QuestionsFrontendIntegrationTest {
    private static @NonNull List<@NonNull Question> questions(int count) {
        return IntStream.range(0, count)
                .mapToObj(
                        index ->
                                new Question(
                                        "Choice",
                                        "q_" + index,
                                        "Which option?",
                                        List.of(
                                                new Option("A (Recommended)", "First."),
                                                new Option("B", "Second."))))
                .toList();
    }

    @Test
    void tenQuestionBatchRoundTripsThroughHttpAndCannotBeAnsweredTwice() throws Exception {
        try (var fixture = new QuestionActionFixture()) {
            UserContext.set("alice");
            var future = fixture.runtime.register(fixture.invocation("call"), questions(10));
            perform(fixture, "list", Map.of())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items[0].questions.length()").value(10));
            Map<String, String> answers = new LinkedHashMap<>();
            for (int i = 0; i < 10; i++) answers.put("q_" + i, i == 9 ? "custom answer" : "A");
            var args = Map.<String, Object>of("callId", "call", "answers", answers);
            perform(fixture, "answer", args).andExpect(status().isOk());
            assertEquals(answers, future.join().answers());
            perform(fixture, "list", Map.of()).andExpect(jsonPath("$.items.length()").value(0));
            perform(fixture, "answer", args).andExpect(status().isBadRequest());
        }
    }

    @Test
    void malformedAnswersDoNotConsumePendingBatch() throws Exception {
        try (var fixture = new QuestionActionFixture()) {
            UserContext.set("alice");
            var future = fixture.runtime.register(fixture.invocation("call"), questions(1));
            for (String body :
                    List.of(
                            "{}",
                            "{\"answers\":null}",
                            "{\"answers\":{}}",
                            "{\"answers\":{\"q_0\":null}}",
                            "{\"answers\":{\"q_0\":\" \"}}",
                            "{\"answers\":{\"wrong\":\"A\"}}",
                            "{\"answers\":{\"q_0\":\"A\",\"extra\":\"B\"}}")) {
                var node = new ObjectMapper().readTree(body);
                var arguments = new LinkedHashMap<String, Object>();
                arguments.put("callId", "call");
                if (node.has("answers")) arguments.put("answers", node.path("answers"));
                perform(fixture, "answer", arguments).andExpect(status().isBadRequest());
                assertFalse(future.isDone());
                assertEquals(1, fixture.runtime.pendingFor(fixture.scope).size());
            }
            perform(fixture, "cancel", Map.of("callId", "call")).andExpect(status().isOk());
            assertTrue(future.join().cancelled());
            perform(fixture, "cancel", Map.of("callId", "call")).andExpect(status().isBadRequest());
        }
    }

    @Test
    void actionsRequireAuthenticationSessionOwnershipMembershipAndSelectedPlugin()
            throws Exception {
        try (var fixture = new QuestionActionFixture()) {
            var future = fixture.runtime.register(fixture.invocation("call"), questions(1));
            UserContext.clear();
            perform(fixture, "list", Map.of()).andExpect(status().isUnauthorized());
            perform(fixture, "cancel", Map.of("callId", "call"))
                    .andExpect(status().isUnauthorized());
            perform(fixture, "answer", Map.of("callId", "call", "answers", Map.of("q_0", "A")))
                    .andExpect(status().isUnauthorized());
            UserContext.set("bob");
            perform(fixture, "list", Map.of()).andExpect(status().isNotFound());
            UserContext.set("alice");
            var foreign = new LinkedHashMap<>(fixture.action("cancel", Map.of("callId", "call")));
            foreign.put("agentId", "foreign-agent");
            fixture.mvc
                    .perform(
                            post("/api/sessions/session/plugin-frontend/actions")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(new ObjectMapper().writeValueAsString(foreign)))
                    .andExpect(status().isNotFound());
            when(fixture.selected.bindings(fixture.session.getId())).thenReturn(List.of());
            perform(fixture, "list", Map.of()).andExpect(status().isNotFound());
            perform(fixture, "cancel", Map.of("callId", "call")).andExpect(status().isNotFound());
            assertFalse(future.isDone());
        }
    }

    private static @NonNull ResultActions perform(
            @NonNull QuestionActionFixture fixture,
            @NonNull String action,
            @NonNull Map<String, ?> args)
            throws Exception {
        return fixture.mvc.perform(
                post("/api/sessions/session/plugin-frontend/actions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                new ObjectMapper()
                                        .writeValueAsString(fixture.action(action, args))));
    }
}
