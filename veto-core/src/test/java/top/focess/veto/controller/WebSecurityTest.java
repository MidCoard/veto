package top.focess.veto.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import top.focess.veto.session.QuoteCheckService;
import top.focess.veto.session.SessionService;
import top.focess.veto.session.SessionService.SessionConfig;
import top.focess.veto.vault.CurrentUser;
import top.focess.veto.vault.ExecutionSecurity;
import top.focess.veto.vault.KeysteadVault;
import top.focess.veto.vault.SessionManager;
import top.focess.veto.vault.TestUsers;
import top.focess.veto.vault.UserEntity;
import top.focess.veto.vault.UserRegistry;

/** Exercises the production security chain, including real MVC async dispatches. */
@SpringJUnitConfig(WebSecurityTest.Config.class)
@WebAppConfiguration
class WebSecurityTest {
    private final @NonNull SessionManager sessions;
    private final @NonNull UserRegistry users;
    private final @NonNull ProbeController controller;
    private final @NonNull ThreadPoolTaskExecutor worker;
    private final @NonNull MockMvc mvc;
    private final @NonNull String token;
    private final @NonNull SessionService quoteSessions;
    private final @NonNull KeysteadVault vault;
    private final @NonNull QuoteCheckService quotes;

    @Autowired
    WebSecurityTest(
            @NonNull WebApplicationContext context,
            @NonNull SessionManager sessions,
            @NonNull UserRegistry users,
            @NonNull ProbeController controller,
            @NonNull ThreadPoolTaskExecutor worker,
            @NonNull SessionService quoteSessions,
            @NonNull KeysteadVault vault,
            @NonNull QuoteCheckService quotes) {
        this.sessions = sessions;
        this.users = users;
        this.controller = controller;
        this.worker = worker;
        this.quoteSessions = quoteSessions;
        this.vault = vault;
        this.quotes = quotes;
        token = sessions.createSession(TestUsers.OWNER, "security-test");
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @BeforeEach
    void setup() {
        cleanup();
        reset(users);
        var user = mock(UserEntity.class);
        when(user.getRole()).thenReturn("USER");
        when(users.findByUserId(TestUsers.OWNER)).thenReturn(Optional.of(user));
        controller.result = new CompletableFuture<>();
        reset(quoteSessions, vault, quotes);
        when(vault.currentUser()).thenAnswer(invocation -> CurrentUser.id());
        var session = mock(SessionConfig.class);
        when(session.sessionId()).thenReturn("owned-session");
        when(quoteSessions.resolveByName("private", TestUsers.OWNER))
                .thenReturn(Optional.of(session));
    }

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
    }

    private void assertCleared() {
        assertNull(CurrentUser.id());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void synchronousRequestUsesUuidPrincipalAndDoesNotCreateAnHttpSession() throws Exception {
        var result =
                mvc.perform(get("/api/probe/user").header("X-Veto-Session-Token", token))
                        .andExpect(status().isOk())
                        .andExpect(content().string(TestUsers.OWNER.toString()))
                        .andReturn();
        assertNull(result.getRequest().getSession(false));
        assertCleared();
        mvc.perform(get("/api/probe/user")).andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "invalid-token"})
    void asyncDispatchCleansOriginalThreadAndRejectsItsAnonymousReuse(@NonNull String nextToken)
            throws Exception {
        var initial =
                mvc.perform(get("/api/probe/async").header("X-Veto-Session-Token", token))
                        .andExpect(request().asyncStarted())
                        .andReturn();
        assertCleared();
        SecurityContextHolder.setContext(ExecutionSecurity.contextFor(TestUsers.OWNER));
        mvc.perform(get("/api/probe/user").header("X-Veto-Session-Token", nextToken))
                .andExpect(status().isUnauthorized());
        assertCleared();
        assertTrue(controller.result.complete("finished"));
        completeOnAnotherThread(initial, 200);
        assertCleared();
    }

    private void completeOnAnotherThread(@NonNull MvcResult initial, int expectedStatus)
            throws Exception {
        var originalThread = Thread.currentThread();
        try (var executor = Executors.newSingleThreadExecutor()) {
            assertTrue(
                    executor.submit(
                                    () -> {
                                        try {
                                            assertTrue(originalThread != Thread.currentThread());
                                            assertCleared();
                                            mvc.perform(asyncDispatch(initial))
                                                    .andExpect(status().is(expectedStatus));
                                            assertCleared();
                                            return Boolean.TRUE;
                                        } finally {
                                            cleanup();
                                        }
                                    })
                            .get(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void revokedTokenIsRejectedOnAsyncRedispatch() throws Exception {
        var initial =
                mvc.perform(get("/api/probe/async").header("X-Veto-Session-Token", token))
                        .andExpect(request().asyncStarted())
                        .andReturn();
        assertCleared();
        sessions.invalidate(token);
        assertTrue(controller.result.complete("finished"));
        completeOnAnotherThread(initial, 401);
    }

    @Test
    void asyncFailureClearsBothContexts() throws Exception {
        var initial =
                mvc.perform(get("/api/probe/async").header("X-Veto-Session-Token", token))
                        .andExpect(request().asyncStarted())
                        .andReturn();
        assertCleared();
        assertTrue(
                controller.result.completeExceptionally(
                        new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE)));
        completeOnAnotherThread(initial, 503);
    }

    @Test
    void callableWorkerSeesSecurityIdentityAndReusedWorkerIsCleaned() throws Exception {
        var initial =
                mvc.perform(get("/api/probe/callable").header("X-Veto-Session-Token", token))
                        .andExpect(request().asyncStarted())
                        .andReturn();
        assertCleared();
        assertTrue(
                worker.submitCompletable(
                                () -> {
                                    assertCleared();
                                    return Boolean.TRUE;
                                })
                        .get(5, TimeUnit.SECONDS));
        assertEquals(TestUsers.OWNER.toString(), initial.getAsyncResult(5000));
        mvc.perform(asyncDispatch(initial))
                .andExpect(status().isOk())
                .andExpect(content().string(TestUsers.OWNER.toString()));
        assertCleared();
    }

    @Test
    void authenticationAndControllerExceptionsCannotLeaveContextBehind() throws Exception {
        mvc.perform(get("/api/probe/failure").header("X-Veto-Session-Token", token))
                .andExpect(status().isBadRequest());
        assertCleared();
        when(users.findByUserId(TestUsers.OWNER))
                .thenThrow(new IllegalStateException("lookup failed"));
        assertThrows(
                Exception.class,
                () -> mvc.perform(get("/api/probe/user").header("X-Veto-Session-Token", token)));
        assertCleared();
    }

    @Test
    void adminRolesAreCheckedFreshAndDeletedAccountsAreRejected() throws Exception {
        mvc.perform(get("/api/plugins").header("X-Veto-Session-Token", token))
                .andExpect(status().isForbidden());
        var admin = mock(UserEntity.class);
        when(admin.getRole()).thenReturn("ADMIN");
        when(users.findByUserId(TestUsers.OWNER)).thenReturn(Optional.of(admin));
        mvc.perform(get("/api/plugins").header("X-Veto-Session-Token", token))
                .andExpect(status().isOk());
        when(users.findByUserId(TestUsers.OWNER)).thenReturn(Optional.empty());
        mvc.perform(get("/api/probe/user").header("X-Veto-Session-Token", token))
                .andExpect(status().isUnauthorized());
        assertCleared();
    }

    @Test
    void publicRoutesPreflightAndWebSocketQueryRemainSupported() throws Exception {
        for (String route :
                new String[] {"/api/auth/status", "/api/system/info", "/actuator/health"}) {
            mvc.perform(get(route)).andExpect(status().isOk());
        }
        mvc.perform(post("/api/auth/login")).andExpect(status().isOk());
        mvc.perform(post("/api/auth/setup")).andExpect(status().isOk());
        mvc.perform(
                        options("/api/probe/user")
                                .header("Origin", "http://localhost:5177")
                                .header("Access-Control-Request-Method", "GET")
                                .header("Access-Control-Request-Headers", "X-Veto-Session-Token"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5177"));
        mvc.perform(
                        options("/api/probe/user")
                                .header("Origin", "https://untrusted.example")
                                .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden());
        mvc.perform(
                        get("/ws/veto/bus/123/session/websocket")
                                .servletPath("/ws/veto/bus/123/session/websocket")
                                .param("token", token))
                .andExpect(status().isOk());
        mvc.perform(get("/api/probe/user").param("token", token))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/unmapped")).andExpect(status().isUnauthorized());
        assertCleared();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void mvcRunsProductionQuotationCallableWithIdentityAndCleansWorker(boolean fail)
            throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var requestThread = Thread.currentThread();
        when(quotes.check("owned-session", "agent", 2, "> quote"))
                .thenAnswer(
                        invocation -> {
                            started.countDown();
                            assertTrue(release.await(5, TimeUnit.SECONDS));
                            assertNotSame(requestThread, Thread.currentThread());
                            assertEquals(TestUsers.OWNER, CurrentUser.id());
                            if (fail)
                                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
                            return List.of();
                        });
        try {
            var initial =
                    mvc.perform(
                                    post("/api/sessions/private/agents/agent/records/2/quote-check")
                                            .header("X-Veto-Session-Token", token)
                                            .contentType("application/json")
                                            .content("{\"body\":\"> quote\"}"))
                            .andExpect(request().asyncStarted())
                            .andReturn();
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertCleared();
            var asyncContext = initial.getRequest().getAsyncContext();
            if (asyncContext == null)
                throw new AssertionError("MVC did not start async processing");
            assertEquals(10_000L, asyncContext.getTimeout());
            mvc.perform(get("/api/probe/user")).andExpect(status().isUnauthorized());
            release.countDown();
            if (fail) assertInstanceOf(ResponseStatusException.class, initial.getAsyncResult(5000));
            else assertEquals(List.of(), initial.getAsyncResult(5000));
            assertTrue(
                    worker.submitCompletable(
                                    () -> {
                                        assertCleared();
                                        return Boolean.TRUE;
                                    })
                            .get(5, TimeUnit.SECONDS));
            completeOnAnotherThread(initial, fail ? 503 : 200);
            assertCleared();
        } finally {
            release.countDown();
        }
    }

    @Configuration
    @EnableWebMvc
    @Import({WebSecurityConfig.class, WebConfig.class, QuoteCheckController.class})
    static class Config implements WebMvcConfigurer {
        @Bean
        @NonNull SessionManager sessions() {
            return new SessionManager();
        }

        @Bean
        @NonNull UserRegistry users() {
            return mock(UserRegistry.class);
        }

        @Bean
        @NonNull ProbeController controller() {
            return new ProbeController();
        }

        @Bean
        @NonNull SessionService quoteSessions() {
            return mock(SessionService.class);
        }

        @Bean
        @NonNull KeysteadVault vault() {
            return mock(KeysteadVault.class);
        }

        @Bean
        @NonNull QuoteCheckService quotes() {
            return mock(QuoteCheckService.class);
        }

        @Bean(initMethod = "initialize", destroyMethod = "shutdown")
        @NonNull ThreadPoolTaskExecutor worker() {
            var executor = new ThreadPoolTaskExecutor();
            executor.setCorePoolSize(1);
            executor.setMaxPoolSize(1);
            executor.setThreadNamePrefix("security-callable-");
            return executor;
        }

        @Override
        public void configureAsyncSupport(@NonNull AsyncSupportConfigurer configurer) {
            configurer.setTaskExecutor(worker());
        }
    }

    @RestController
    static class ProbeController {
        private @NonNull CompletableFuture<@NonNull String> result = new CompletableFuture<>();

        @GetMapping({"/api/probe/user", "/ws/veto/bus/123/session/websocket"})
        public @NonNull String user() {
            var userId = CurrentUser.id();
            if (userId == null) throw new AssertionError("Missing authenticated UUID");
            var authentication = SecurityContextHolder.getContext().getAuthentication();
            assertNotNull(authentication);
            assertEquals(userId, authentication.getPrincipal());
            return userId.toString();
        }

        @GetMapping("/api/probe/async")
        public @NonNull CompletableFuture<@NonNull String> async() {
            assertEquals(TestUsers.OWNER.toString(), user());
            return result;
        }

        @GetMapping("/api/probe/callable")
        public @NonNull Callable<@NonNull String> callable() {
            return this::user;
        }

        @GetMapping("/api/probe/failure")
        public @NonNull String failure() {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }

        @GetMapping({"/api/auth/status", "/api/system/info", "/actuator/health", "/api/plugins"})
        public @NonNull String status() {
            return "ok";
        }

        @PostMapping({"/api/auth/login", "/api/auth/setup"})
        public @NonNull String login() {
            return "ok";
        }
    }
}
