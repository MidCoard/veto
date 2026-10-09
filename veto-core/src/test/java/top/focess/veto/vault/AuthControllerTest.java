package top.focess.veto.vault;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import top.focess.veto.command.PromptHandler;
import top.focess.veto.controller.AuthController;
import top.focess.veto.controller.dto.AuthCredentials;
import top.focess.veto.controller.dto.AuthLoginResponse;
import top.focess.veto.controller.dto.CreateUserRequest;
import top.focess.veto.controller.dto.RestResponse;
import top.focess.veto.event.EventManager;
import top.focess.veto.security.SignupPolicy;
import top.focess.veto.terminal.IpcServer;

class AuthControllerTest {

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void adminProvisioningChecksAuthorityAfterWaitingForLifecycleLock(boolean revokeToken)
            throws Exception {
        var users = mock(UserRegistry.class);
        var sessions = new LoginSessionManager();
        var userId = UUID.randomUUID();
        var token = sessions.createLoginSession(userId, "administrator");
        var admin = mock(UserEntity.class);
        var administrator = new AtomicBoolean(true);
        var requestStarted = new CountDownLatch(1);
        when(users.isAdmin(userId)).thenAnswer(invocation -> administrator.get());
        when(admin.getRole())
                .thenAnswer(
                        invocation -> {
                            return administrator.get()
                                    ? UserRegistry.Role.ADMIN
                                    : UserRegistry.Role.USER;
                        });
        var vault = mock(KeysteadVault.class);
        var lifecycle = mock(AuthLifecycleManager.class);
        when(lifecycle.locks()).thenReturn(new AccountLocks());
        var controller = controller(users, sessions, vault, lifecycle);
        try (var pool = Executors.newSingleThreadExecutor()) {
            Future<@NonNull ResponseEntity<RestResponse>> result;
            try (var _ = lifecycle.locks().exclusive()) {
                result =
                        pool.submit(
                                () -> {
                                    requestStarted.countDown();
                                    return http(
                                            controller,
                                            () ->
                                                    controller.addUser(
                                                            token,
                                                            new CreateUserRequest(
                                                                    "new-user",
                                                                    "test-password",
                                                                    "USER")));
                                });
                assertTrue(requestStarted.await(5, TimeUnit.SECONDS));
                if (revokeToken) sessions.revokeToken(token);
                else administrator.set(false);
            }

            assertEquals(
                    revokeToken ? 401 : 403,
                    result.get(5, TimeUnit.SECONDS).getStatusCode().value());
            verify(users, never()).create(anyString(), anyString(), anyString());
            verifyNoInteractions(vault);
            verify(lifecycle, never()).signup(any(UserEntity.class), anyString());
            verify(lifecycle, never()).login(any(UserEntity.class), anyString());
        }
    }

    @Test
    void wrongPasswordCannotReuseAnAlreadyUnlockedVault(@TempDir @NonNull Path tempDir) {
        var users = mock(UserRegistry.class);
        var owner = mock(UserEntity.class);
        var userId = UUID.randomUUID();
        when(owner.getUserId()).thenReturn(userId);
        when(owner.getUsername()).thenReturn("owner");
        when(users.findByUserId(userId)).thenReturn(Optional.of(owner));
        when(users.findByUsername("owner")).thenReturn(Optional.of(owner));
        when(users.authenticate("owner", "wrong-password")).thenReturn(Optional.empty());
        var config = new CredentialVaultConfiguration();
        config.setVaultHome(tempDir.toString());
        var vault = new KeysteadVault(config, users);
        var sessions = new LoginSessionManager();
        var existingToken = sessions.createLoginSession(userId, "owner");
        var events = mock(EventManager.class);
        var lifecycle =
                new AuthLifecycleManager(
                        vault,
                        mock(PromptHandler.class),
                        events,
                        sessions,
                        new StaticListableBeanFactory().getBeanProvider(IpcServer.class));
        var controller = controller(users, sessions, vault, lifecycle);
        try {
            vault.createVault(userId, "test-password");
            assertEquals(userId, vault.login("owner", "test-password"));
            assertTrue(vault.isUnlocked(userId));

            var response =
                    http(
                            controller,
                            () -> controller.login(new AuthCredentials("owner", "wrong-password")));

            assertEquals(401, response.getStatusCode().value());
            assertEquals(1, sessions.activeLoginSessionCount());
            assertTrue(sessions.validateToken(existingToken).isPresent());
            assertTrue(vault.isUnlocked(userId));
            verify(users).authenticate("owner", "wrong-password");
            verifyNoInteractions(events);
        } finally {
            vault.logoutAll();
        }
    }

    @Test
    void setupCannotPublishATokenAfterConcurrentLogout() throws Exception {
        var users = mock(UserRegistry.class);
        var lifecycle = mock(AuthLifecycleManager.class);
        when(lifecycle.locks()).thenReturn(new AccountLocks());
        var vault = mock(KeysteadVault.class);
        var sessions = new LoginSessionManager();
        var userId = UUID.randomUUID();
        var user = mock(UserEntity.class);
        when(user.getUserId()).thenReturn(userId);
        when(user.getUsername()).thenReturn("owner");
        when(user.getRole()).thenReturn(UserRegistry.Role.ADMIN);
        when(users.create("owner", "test-password", UserRegistry.Role.ADMIN)).thenReturn(user);
        var registering = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var logoutStarted = new CountDownLatch(1);
        var logoutEntered = new CountDownLatch(1);
        doAnswer(
                        invocation -> {
                            registering.countDown();
                            assertTrue(release.await(5, TimeUnit.SECONDS));
                            return null;
                        })
                .when(lifecycle)
                .signup(user, "test-password");
        doAnswer(
                        invocation -> {
                            logoutEntered.countDown();
                            return null;
                        })
                .when(vault)
                .logout(userId);
        var controller = controller(users, sessions, vault, lifecycle);
        var revoker =
                new AuthLifecycleManager(
                        vault,
                        mock(PromptHandler.class),
                        mock(EventManager.class),
                        sessions,
                        new StaticListableBeanFactory().getBeanProvider(IpcServer.class));
        when(lifecycle.locks()).thenReturn(revoker.locks());
        try (var pool = Executors.newFixedThreadPool(2)) {
            var setup =
                    pool.submit(
                            () -> controller.setup(new AuthCredentials("owner", "test-password")));
            assertTrue(registering.await(5, TimeUnit.SECONDS));
            var logout =
                    pool.submit(
                            () -> {
                                logoutStarted.countDown();
                                revoker.logout(userId);
                            });
            assertTrue(logoutStarted.await(5, TimeUnit.SECONDS));
            try {
                assertFalse(logoutEntered.await(200, TimeUnit.MILLISECONDS));
            } finally {
                release.countDown();
            }
            assertEquals(200, setup.get(5, TimeUnit.SECONDS).getStatusCode().value());
            logout.get(5, TimeUnit.SECONDS);
            assertFalse(sessions.hasLoginSessions(userId));
        } finally {
            release.countDown();
        }
    }

    @Test
    void concurrentFirstRunSetupsCannotBothCreateAdministrators() throws Exception {
        var users = mock(UserRegistry.class);
        var lifecycle = mock(AuthLifecycleManager.class);
        when(lifecycle.locks()).thenReturn(new AccountLocks());
        var sessions = new LoginSessionManager();
        var controller = controller(users, sessions, mock(KeysteadVault.class), lifecycle);
        var exists = new AtomicBoolean();
        var checks = new AtomicInteger();
        var creating = new CountDownLatch(1);
        var secondCheck = new CountDownLatch(1);
        var secondStarted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var user = mock(UserEntity.class);
        when(user.getUserId()).thenReturn(UUID.randomUUID());
        when(user.getUsername()).thenReturn("first");
        when(user.getRole()).thenReturn(UserRegistry.Role.ADMIN);
        when(users.anyUserExists())
                .thenAnswer(
                        invocation -> {
                            if (checks.incrementAndGet() > 1) secondCheck.countDown();
                            return exists.get();
                        });
        when(users.create(anyString(), anyString(), eq(UserRegistry.Role.ADMIN)))
                .thenAnswer(
                        invocation -> {
                            creating.countDown();
                            assertTrue(release.await(5, TimeUnit.SECONDS));
                            exists.set(true);
                            return user;
                        });
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first =
                    pool.submit(
                            () -> controller.setup(new AuthCredentials("first", "test-password")));
            assertTrue(creating.await(5, TimeUnit.SECONDS));
            var second =
                    pool.submit(
                            () -> {
                                secondStarted.countDown();
                                return http(
                                        controller,
                                        () ->
                                                controller.setup(
                                                        new AuthCredentials(
                                                                "second", "test-password")));
                            });
            assertTrue(secondStarted.await(5, TimeUnit.SECONDS));
            try {
                assertFalse(
                        secondCheck.await(200, TimeUnit.MILLISECONDS),
                        "Second setup must wait until first registration is committed");
            } finally {
                release.countDown();
            }
            assertEquals(200, first.get(5, TimeUnit.SECONDS).getStatusCode().value());
            assertEquals(409, second.get(5, TimeUnit.SECONDS).getStatusCode().value());
            verify(users, times(1)).create(anyString(), anyString(), eq(UserRegistry.Role.ADMIN));
            assertEquals(1, sessions.activeLoginSessionCount());
        } finally {
            release.countDown();
        }
    }

    @Test
    void loginReadsChangedPasswordOnlyAfterTheAccountOperationCompletes() throws Exception {
        var users = mock(UserRegistry.class);
        var lifecycle = mock(AuthLifecycleManager.class);
        when(lifecycle.locks()).thenReturn(new AccountLocks());
        var sessions = new LoginSessionManager();
        var vault = mock(KeysteadVault.class);
        var controller = controller(users, sessions, vault, lifecycle);
        var started = new CountDownLatch(1);
        var authenticating = new CountDownLatch(1);
        var owner = mock(UserEntity.class);
        var ownerId = UUID.randomUUID();
        when(owner.getUserId()).thenReturn(ownerId);
        when(users.findByUsername("owner")).thenReturn(Optional.of(owner));
        when(users.authenticate("owner", "old-password"))
                .thenAnswer(
                        call -> {
                            authenticating.countDown();
                            assertTrue(lifecycle.locks().heldByCurrentThread(ownerId));
                            return Optional.empty();
                        });
        try (var pool = Executors.newSingleThreadExecutor()) {
            Future<@NonNull ResponseEntity<RestResponse>> result;
            try (var _ = lifecycle.locks().account(ownerId)) {
                result =
                        pool.submit(
                                () -> {
                                    started.countDown();
                                    return http(
                                            controller,
                                            () ->
                                                    controller.login(
                                                            new AuthCredentials(
                                                                    "owner", "old-password")));
                                });
                assertTrue(started.await(5, TimeUnit.SECONDS));
                assertFalse(authenticating.await(200, TimeUnit.MILLISECONDS));
            }
            assertEquals(401, result.get(5, TimeUnit.SECONDS).getStatusCode().value());
            assertEquals(0, sessions.activeLoginSessionCount());
            verifyNoInteractions(vault);
        }
    }

    @Test
    void anotherAccountCanLoginAndLogoutWhileAliceIsOpeningHerVault() throws Exception {
        var users = mock(UserRegistry.class);
        var alice = mock(UserEntity.class);
        var bob = mock(UserEntity.class);
        var aliceId = new UUID(0, 1);
        var bobId = new UUID(0, 2);
        when(alice.getUserId()).thenReturn(aliceId);
        when(alice.getUsername()).thenReturn("alice");
        when(users.findByUsername("alice")).thenReturn(Optional.of(alice));
        when(alice.getRole()).thenReturn(UserRegistry.Role.USER);
        when(bob.getUserId()).thenReturn(bobId);
        when(bob.getUsername()).thenReturn("bob");
        when(users.findByUsername("bob")).thenReturn(Optional.of(bob));
        when(bob.getRole()).thenReturn(UserRegistry.Role.USER);
        when(users.authenticate("alice", "test-password")).thenReturn(Optional.of(alice));
        var bobAuthenticating = new CountDownLatch(1);
        when(users.authenticate("bob", "test-password"))
                .thenAnswer(
                        call -> {
                            bobAuthenticating.countDown();
                            return Optional.of(bob);
                        });
        var sessions = new LoginSessionManager();
        var vault = mock(KeysteadVault.class);
        var lifecycle =
                new AuthLifecycleManager(
                        vault,
                        mock(PromptHandler.class),
                        mock(EventManager.class),
                        sessions,
                        new StaticListableBeanFactory().getBeanProvider(IpcServer.class));
        var opening = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(vault.login("alice", "test-password"))
                .thenAnswer(
                        call -> {
                            opening.countDown();
                            assertTrue(release.await(5, TimeUnit.SECONDS));
                            return aliceId;
                        });
        when(vault.login("bob", "test-password")).thenReturn(bobId);
        var controller = controller(users, sessions, vault, lifecycle);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first =
                    pool.submit(
                            () -> controller.login(new AuthCredentials("alice", "test-password")));
            assertTrue(opening.await(5, TimeUnit.SECONDS));
            var second =
                    pool.submit(
                            () -> controller.login(new AuthCredentials("bob", "test-password")));
            try {
                assertTrue(bobAuthenticating.await(5, TimeUnit.SECONDS));
                var bobResponse = second.get(5, TimeUnit.SECONDS);
                assertEquals(200, bobResponse.getStatusCode().value());
                var bobToken =
                        assertInstanceOf(AuthLoginResponse.class, bobResponse.getBody()).token();
                assertEquals(200, controller.logout(bobToken).getStatusCode().value());
                assertFalse(first.isDone());
            } finally {
                release.countDown();
            }
            assertEquals(200, first.get(5, TimeUnit.SECONDS).getStatusCode().value());
            assertEquals(200, second.get(5, TimeUnit.SECONDS).getStatusCode().value());
            assertFalse(sessions.hasLoginSessions(bobId));
            verify(vault).logout(bobId);
        } finally {
            release.countDown();
        }
    }

    @Test
    void lastOwnerTokenLogoutClosesItsVaultWhileAnotherOwnerRemainsLoggedIn() throws Exception {
        var sessions = new LoginSessionManager();
        var aliceId = UUID.randomUUID();
        var aliceToken = sessions.createLoginSession(aliceId, "alice");
        var bobToken = sessions.createLoginSession(UUID.randomUUID(), "bob");
        var lifecycle = mock(AuthLifecycleManager.class);
        when(lifecycle.locks()).thenReturn(new AccountLocks());
        var mvc =
                MockMvcBuilders.standaloneSetup(
                                controller(
                                        mock(UserRegistry.class),
                                        sessions,
                                        mock(KeysteadVault.class),
                                        lifecycle))
                        .build();
        mvc.perform(post("/api/auth/logout").header("X-Veto-Session-Token", aliceToken))
                .andExpect(status().isOk());
        verify(lifecycle).logout(aliceId);
        assertTrue(sessions.validateToken(bobToken).isPresent());
    }

    @Test
    void invalidRegistrationNeverCreatesAUserOrVault() throws Exception {
        UserRegistry users = mock(UserRegistry.class);
        LoginSessionManager sessions = mock(LoginSessionManager.class);
        KeysteadVault vault = mock(KeysteadVault.class);
        AuthLifecycleManager lifecycle = mock(AuthLifecycleManager.class);
        when(lifecycle.locks()).thenReturn(new AccountLocks());
        var mvc =
                MockMvcBuilders.standaloneSetup(controller(users, sessions, vault, lifecycle))
                        .build();
        for (String body :
                List.of(
                        "{}",
                        "{\"username\":\"alice\",\"password\":null}",
                        "{\"username\":\"../invalid\",\"password\":\"password123\"}",
                        "{\"username\":\"alice\",\"password\":\"short\"}")) {
            mvc.perform(
                            post("/api/auth/setup")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(users, sessions, vault, lifecycle);
    }

    private static @NonNull ResponseEntity<RestResponse> http(
            @NonNull AuthController controller,
            @NonNull Supplier<ResponseEntity<RestResponse>> operation) {
        try {
            return operation.get();
        } catch (AuthException rejected) {
            return controller.rejected(rejected);
        }
    }

    private static @NonNull AuthController controller(
            @NonNull UserRegistry users,
            @NonNull LoginSessionManager sessions,
            @NonNull KeysteadVault vault,
            @NonNull AuthLifecycleManager lifecycle) {
        var accounts = mock(UserAdminService.class);

        when(accounts.create(anyString(), anyString(), anyString()))
                .thenAnswer(
                        invocation ->
                                users.create(
                                        invocation.getArgument(0),
                                        invocation.getArgument(1),
                                        invocation.getArgument(2)));
        return new AuthController(
                new AuthService(
                        users,
                        sessions,
                        vault,
                        lifecycle,
                        accounts,
                        new SignupPolicy("public", "LOCAL")));
    }
}
