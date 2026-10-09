package top.focess.veto.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import top.focess.veto.agent.AgentService;
import top.focess.veto.agent.ProtectedInputException;
import top.focess.veto.agent.RequestHandle;
import top.focess.veto.agent.VetoAgent;
import top.focess.veto.api.llm.ProviderType;
import top.focess.veto.bus.DeltaBroker;
import top.focess.veto.command.SessionCommandService;
import top.focess.veto.contract.EventFrame;
import top.focess.veto.session.LlmConfig;
import top.focess.veto.session.SessionService;
import top.focess.veto.session.SessionService.SessionConfig;
import top.focess.veto.vault.CurrentUser;
import top.focess.veto.vault.KeysteadVault;
import top.focess.veto.vault.LoginSessionManager;
import top.focess.veto.vault.TestUsers;
import top.focess.veto.vault.UserRegistry;

/** Exercises server-owned input routing through the production security chain. */
@SpringJUnitConfig(SessionCommandMvcTest.Config.class)
@WebAppConfiguration
class SessionCommandMvcTest {
    private static final @NonNull String SESSION_ID = "e2959fae-9fb0-450a-8cc5-2ddb226708b8";
    private final @NonNull MockMvc mvc;
    private final @NonNull String token;
    private final @NonNull SessionService sessions;
    private final @NonNull AgentService agents;
    private final @NonNull DeltaBroker broker;

    @Autowired
    SessionCommandMvcTest(
            @NonNull WebApplicationContext context,
            @NonNull LoginSessionManager logins,
            @NonNull SessionService sessions,
            @NonNull AgentService agents,
            @NonNull DeltaBroker broker) {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        token = logins.createLoginSession(TestUsers.OWNER, "input-routing-test");
        this.sessions = sessions;
        this.agents = agents;
        this.broker = broker;
    }

    @BeforeEach
    void setup() {
        cleanup();
        reset(sessions, agents, broker);
    }

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
    }

    private void ownedSession() {
        var owned = mock(SessionConfig.class);
        var config = new LlmConfig(ProviderType.DEEPSEEK, "test-model", "test-reference");
        when(owned.sessionId()).thenReturn(SESSION_ID);
        when(owned.config()).thenReturn(config);

        when(sessions.activateForRest("private", TestUsers.OWNER)).thenReturn(Optional.of(owned));
    }

    @Test
    void unauthenticatedInputCannotActivateSession() throws Exception {
        mvc.perform(
                        post("/api/sessions/private/prompt")
                                .contentType("application/json")
                                .content("{\"prompt\":\"/compact\"}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(sessions, agents, broker);
        assertNull(CurrentUser.id());
    }

    @Test
    void foreignSessionCannotRunCommandOrPublishNotice() throws Exception {
        when(sessions.activateForRest("private", TestUsers.OWNER)).thenReturn(Optional.empty());
        mvc.perform(
                        post("/api/sessions/private/prompt")
                                .header("X-Veto-Session-Token", token)
                                .contentType("application/json")
                                .content("{\"prompt\":\"/compact\"}"))
                .andExpect(status().isNotFound());
        verifyNoInteractions(agents, broker);
        assertNull(CurrentUser.id());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                " /compact ",
                "/compact extra",
                "/compact\textra",
                "/compact\nextra",
                "/compact \"quoted argument\"",
                "/compact /login"
            })
    void compactIsEnqueuedWithoutSubmittingModelPromptAndContextIsCleared(@NonNull String prompt)
            throws Exception {
        ownedSession();
        var agent = mock(VetoAgent.class);
        var request = mock(RequestHandle.class);
        when(agents.agentsView()).thenReturn(Map.of(SESSION_ID, agent));
        when(request.requestId()).thenReturn(UUID.randomUUID().toString());
        when(agent.compact())
                .thenAnswer(
                        invocation -> {
                            assertEquals(TestUsers.OWNER, CurrentUser.id());
                            return request;
                        });
        mvc.perform(
                        post("/api/sessions/private/prompt")
                                .header("X-Veto-Session-Token", token)
                                .contentType("application/json")
                                .content(
                                        new ObjectMapper()
                                                .writeValueAsString(Map.of("prompt", prompt))))
                .andExpect(status().isAccepted())
                .andExpect(request().asyncNotStarted())
                .andExpect(jsonPath("$.sessionId").value(SESSION_ID));
        verify(agent).compact();
        verify(agents, never()).submitNow(anyString(), anyString(), any(), any());
        verifyNoInteractions(broker);
        assertNull(CurrentUser.id());
        mvc.perform(
                        post("/api/sessions/private/prompt")
                                .contentType("application/json")
                                .content("{\"prompt\":\"ordinary\"}"))
                .andExpect(status().isUnauthorized());
        assertNull(CurrentUser.id());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "/signup synthetic-private-argument",
                "/login",
                "/unknown",
                "/compact-extra",
                "/compacted"
            })
    void unknownSlashInputRemainsUnchangedPromptWithGenericTooltip(@NonNull String prompt)
            throws Exception {
        ownedSession();
        mvc.perform(
                        post("/api/sessions/private/prompt")
                                .header("X-Veto-Session-Token", token)
                                .contentType("application/json")
                                .content(
                                        new ObjectMapper()
                                                .writeValueAsString(Map.of("prompt", prompt))))
                .andExpect(status().isAccepted());
        verify(agents).submitNow(eq(SESSION_ID), eq(prompt), any(), eq(TestUsers.OWNER));
        verify(agents, never()).agentsView();
        var frame = ArgumentCaptor.forClass(EventFrame.class);
        verify(broker).publish(frame.capture());
        assertEquals(EventFrame.Kind.NOTICE, frame.getValue().kind());
        assertEquals(UUID.fromString(SESSION_ID), frame.getValue().sessionId());
        assertFalse(frame.getValue().text().isBlank());
        assertFalse(frame.getValue().text().contains(prompt.strip()));
        assertFalse(frame.getValue().text().contains("synthetic-private-argument"));
        assertFalse(frame.getValue().attrs().containsKey("agentId"));
        assertNull(CurrentUser.id());
    }

    @Test
    void ordinaryPromptHasNoTooltip() throws Exception {
        ownedSession();
        mvc.perform(
                        post("/api/sessions/private/prompt")
                                .header("X-Veto-Session-Token", token)
                                .contentType("application/json")
                                .content("{\"prompt\":\"Explain TCP\"}"))
                .andExpect(status().isAccepted());
        verify(agents).submitNow(eq(SESSION_ID), eq("Explain TCP"), any(), eq(TestUsers.OWNER));
        verifyNoInteractions(broker);
        assertNull(CurrentUser.id());
    }

    @Test
    void rejectedProtectedSlashInputCannotPublishFallbackNotice() throws Exception {
        ownedSession();
        doThrow(new ProtectedInputException())
                .when(agents)
                .submitNow(anyString(), anyString(), any(), any());
        mvc.perform(
                        post("/api/sessions/private/prompt")
                                .header("X-Veto-Session-Token", token)
                                .contentType("application/json")
                                .content("{\"prompt\":\"/unknown synthetic-private-argument\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PROTECTED_INPUT_UNAVAILABLE"));
        verifyNoInteractions(broker);
        assertNull(CurrentUser.id());
    }

    @Configuration
    @EnableWebMvc
    @Import({
        WebSecurityConfig.class,
        WebConfig.class,
        PromptController.class,
        SessionCommandService.class
    })
    static class Config {
        @Bean
        @NonNull LoginSessionManager logins() {
            return new LoginSessionManager();
        }

        @Bean
        @NonNull UserRegistry users() {
            return TestUsers.registry();
        }

        @Bean
        @NonNull SessionService sessions() {
            return mock(SessionService.class);
        }

        @Bean
        @NonNull AgentService agents() {
            return mock(AgentService.class);
        }

        @Bean
        @NonNull DeltaBroker broker() {
            return mock(DeltaBroker.class);
        }

        @Bean
        @NonNull KeysteadVault vault() {
            var vault = mock(KeysteadVault.class);
            when(vault.currentUser()).thenAnswer(invocation -> CurrentUser.id());
            return vault;
        }
    }
}
