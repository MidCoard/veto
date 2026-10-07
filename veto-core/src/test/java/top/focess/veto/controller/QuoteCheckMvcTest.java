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
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import top.focess.veto.agent.TurnRecord;
import top.focess.veto.agent.TurnType;
import top.focess.veto.memory.TurnRecordEntity;
import top.focess.veto.memory.TurnRecordRepository;
import top.focess.veto.session.QuoteCheckService;
import top.focess.veto.session.SessionService;
import top.focess.veto.session.SessionService.SessionConfig;
import top.focess.veto.vault.CurrentUser;
import top.focess.veto.vault.KeysteadVault;
import top.focess.veto.vault.LoginSessionManager;
import top.focess.veto.vault.TestUsers;
import top.focess.veto.vault.UserEntity;
import top.focess.veto.vault.UserRegistry;

/** Real quotation service failures pass through secured MVC callable execution and redispatch. */
@SpringJUnitConfig(QuoteCheckMvcTest.Config.class)
@WebAppConfiguration
class QuoteCheckMvcTest {
    private final @NonNull MockMvc mvc;
    private final @NonNull String token;
    private final @NonNull TurnRecordRepository records;
    private final @NonNull ObjectMapper mapper;
    private final @NonNull ThreadPoolTaskExecutor worker;

    @Autowired
    QuoteCheckMvcTest(
            @NonNull WebApplicationContext context,
            @NonNull LoginSessionManager sessions,
            @NonNull TurnRecordRepository records,
            @NonNull ObjectMapper mapper,
            @NonNull ThreadPoolTaskExecutor worker) {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        token = sessions.createLoginSession(TestUsers.OWNER, "quotation-test");
        this.records = records;
        this.mapper = mapper;
        this.worker = worker;
    }

    @BeforeEach
    void setup() {
        cleanup();
        reset(records);
    }

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest
    @CsvSource({"missing, 404", "USER_PROMPT, 404", "changed, 409", "ASSISTANT_RESPONSE, 200"})
    void mapsSavedAnswerFailuresWithoutLosingWorkerCleanup(@NonNull String scenario, int expected)
            throws Exception {
        Optional<TurnRecordEntity> saved = Optional.empty();
        if (!scenario.equals("missing")) {
            var type =
                    scenario.equals("USER_PROMPT")
                            ? TurnType.USER_PROMPT
                            : TurnType.ASSISTANT_RESPONSE;
            saved =
                    Optional.of(
                            TurnRecordEntity.of(
                                    new TurnRecord(
                                            2, type, Map.of("content", "saved answer"), null),
                                    UUID.randomUUID(),
                                    TestUsers.OWNER,
                                    "agent",
                                    mapper));
        }
        var result = saved;
        when(records.findBySessionIdAndAgentIdAndTurnNumber("owned-session", "agent", 2))
                .thenAnswer(
                        invocation -> {
                            assertEquals(TestUsers.OWNER, CurrentUser.id());
                            return result;
                        });
        String body = scenario.equals("changed") ? "stale answer" : "saved answer";
        var initial =
                mvc.perform(
                                post("/api/sessions/private/agents/agent/records/2/quote-check")
                                        .header("X-Veto-Session-Token", token)
                                        .contentType("application/json")
                                        .content(mapper.writeValueAsString(Map.of("body", body))))
                        .andExpect(request().asyncStarted())
                        .andReturn();
        assertNull(CurrentUser.id());
        mvc.perform(asyncDispatch(initial)).andExpect(status().is(expected));
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertTrue(
                worker.submitCompletable(
                                () -> {
                                    assertNull(
                                            SecurityContextHolder.getContext().getAuthentication());
                                    return Boolean.TRUE;
                                })
                        .get(5, TimeUnit.SECONDS));
        verify(records).findBySessionIdAndAgentIdAndTurnNumber("owned-session", "agent", 2);
        verifyNoMoreInteractions(records);
    }

    @Configuration
    @EnableWebMvc
    @Import({WebSecurityConfig.class, WebConfig.class, QuoteCheckController.class})
    static class Config implements WebMvcConfigurer {
        @Bean
        @NonNull LoginSessionManager sessions() {
            return new LoginSessionManager();
        }

        @Bean
        @NonNull UserRegistry users() {
            var users = mock(UserRegistry.class);
            var owner = mock(UserEntity.class);
            when(owner.getRole()).thenReturn(UserRegistry.Role.USER);
            when(users.findByUserId(TestUsers.OWNER)).thenReturn(Optional.of(owner));
            return users;
        }

        @Bean
        @NonNull SessionService quoteSessions() {
            var sessions = mock(SessionService.class);
            var owned = mock(SessionConfig.class);
            when(owned.sessionId()).thenReturn("owned-session");
            when(sessions.resolveByName("private", TestUsers.OWNER)).thenReturn(Optional.of(owned));
            return sessions;
        }

        @Bean
        @NonNull KeysteadVault vault() {
            var vault = mock(KeysteadVault.class);
            when(vault.currentUser()).thenAnswer(invocation -> CurrentUser.id());
            return vault;
        }

        @Bean
        @NonNull TurnRecordRepository records() {
            return mock(TurnRecordRepository.class);
        }

        @Bean
        @NonNull ObjectMapper mapper() {
            return new ObjectMapper();
        }

        @Bean
        @NonNull QuoteCheckService quotes(
                @NonNull TurnRecordRepository records, @NonNull ObjectMapper mapper) {
            return new QuoteCheckService(records, mapper);
        }

        @Bean(initMethod = "initialize", destroyMethod = "shutdown")
        @NonNull ThreadPoolTaskExecutor worker() {
            var executor = new ThreadPoolTaskExecutor();
            executor.setCorePoolSize(1);
            executor.setMaxPoolSize(1);
            executor.setThreadNamePrefix("quotation-mvc-");
            return executor;
        }

        @Override
        public void configureAsyncSupport(@NonNull AsyncSupportConfigurer configurer) {
            configurer.setTaskExecutor(worker());
        }
    }
}
