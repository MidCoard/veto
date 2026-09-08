package top.focess.veto.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import top.focess.veto.agent.tool.ToolDocs;
import top.focess.veto.agent.tool.builtin.AskUserTool;
import top.focess.veto.agent.tool.builtin.UserQuestionRegistry;
import top.focess.veto.session.SessionService;
import top.focess.veto.vault.KeysteadVault;

class UserQuestionControllerTest {
    private final @NonNull UserQuestionRegistry registry = new UserQuestionRegistry();
    private final @NonNull SessionService sessions =
            mock(ToolDocs.nonNullClass(SessionService.class));
    private final @NonNull KeysteadVault vault = mock(ToolDocs.nonNullClass(KeysteadVault.class));
    private final @NonNull MockMvc mvc =
            MockMvcBuilders.standaloneSetup(new UserQuestionController(sessions, registry, vault))
                    .build();

    private void login(@NonNull String user, @NonNull String agent) {
        when(vault.currentUser()).thenReturn(user);
        when(sessions.primaryAgentIdFor("session", user)).thenReturn(Optional.of(agent));
    }

    private static @NonNull List<AskUserTool.@NonNull Question> questions(int count) {
        return IntStream.range(0, count)
                .mapToObj(
                        index ->
                                new AskUserTool.Question(
                                        "Choice",
                                        "q_" + index,
                                        "Which option?",
                                        List.of(
                                                new AskUserTool.Option("A (Recommended)", "First."),
                                                new AskUserTool.Option("B", "Second."))))
                .toList();
    }

    @Test
    void tenQuestionBatchRoundTripsThroughHttpAndCannotBeAnsweredTwice() throws Exception {
        login("alice", "alice-agent");
        var future = registry.register("alice-agent", "call", questions(10));
        mvc.perform(get("/api/sessions/session/questions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].questions.length()").value(10));
        Map<String, String> answers = new LinkedHashMap<>();
        for (int i = 0; i < 10; i++)
            answers.put("q_" + i, i == 9 ? "custom answer" : "A (Recommended)");
        String body = new ObjectMapper().writeValueAsString(Map.of("answers", answers));
        mvc.perform(
                        post("/api/sessions/session/questions/call")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isNoContent());
        assertEquals(answers, future.join().answers());
        mvc.perform(get("/api/sessions/session/questions"))
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(
                        post("/api/sessions/session/questions/call")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void malformedAnswersDoNotConsumePendingBatch() throws Exception {
        login("alice", "alice-agent");
        var future = registry.register("alice-agent", "call", questions(1));
        for (String body :
                List.of(
                        "{}",
                        "{\"answers\":null}",
                        "{\"answers\":{}}",
                        "{\"answers\":{\"q_0\":null}}",
                        "{\"answers\":{\"q_0\":\" \"}}",
                        "{\"answers\":{\"wrong\":\"A\"}}",
                        "{\"answers\":{\"q_0\":\"A\",\"extra\":\"B\"}}")) {
            mvc.perform(
                            post("/api/sessions/session/questions/call")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body))
                    .andExpect(status().isBadRequest());
            assertFalse(future.isDone());
            assertEquals(1, registry.pendingFor("alice-agent").size());
        }
        mvc.perform(post("/api/sessions/session/questions/call/cancel"))
                .andExpect(status().isNoContent());
        assertTrue(future.join().cancelled());
        mvc.perform(post("/api/sessions/session/questions/call/cancel"))
                .andExpect(status().isNotFound());
    }

    @Test
    void endpointsRequireAuthenticationAndResolveSessionByCurrentOwner() throws Exception {
        var future = registry.register("alice-agent", "call", questions(1));
        mvc.perform(get("/api/sessions/session/questions")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/sessions/session/questions/call/cancel"))
                .andExpect(status().isUnauthorized());
        mvc.perform(
                        post("/api/sessions/session/questions/call")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"answers\":{\"q_0\":\"A\"}}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(sessions);
        when(vault.currentUser()).thenReturn("bob");
        when(sessions.primaryAgentIdFor("session", "bob")).thenReturn(Optional.empty());
        mvc.perform(get("/api/sessions/session/questions")).andExpect(status().isNotFound());
        login("bob", "bob-agent");
        mvc.perform(get("/api/sessions/session/questions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(post("/api/sessions/session/questions/call/cancel"))
                .andExpect(status().isNotFound());
        mvc.perform(
                        post("/api/sessions/session/questions/call")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"answers\":{\"q_0\":\"A\"}}"))
                .andExpect(status().isBadRequest());
        assertFalse(future.isDone());
        registry.cancel("alice-agent", "call");
    }
}
