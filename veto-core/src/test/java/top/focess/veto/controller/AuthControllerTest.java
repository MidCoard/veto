package top.focess.veto.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import top.focess.veto.controller.dto.AuthCredentials;
import top.focess.veto.vault.AuthLifecycleManager;
import top.focess.veto.vault.KeysteadVault;
import top.focess.veto.vault.SessionManager;
import top.focess.veto.vault.UserEntity;
import top.focess.veto.vault.UserRegistry;

class AuthControllerTest {
    @Test
    void concurrentFirstRunSetupsCannotBothCreateAdministrators() throws Exception {
        var users = mock(UserRegistry.class);
        var lifecycle = mock(AuthLifecycleManager.class);
        var sessions = new SessionManager();
        var controller = new AuthController(users, sessions, mock(KeysteadVault.class), lifecycle);
        var exists = new AtomicBoolean();
        var checks = new AtomicInteger();
        var creating = new CountDownLatch(1);
        var secondCheck = new CountDownLatch(1);
        var secondStarted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var user = mock(UserEntity.class);
        when(user.getUserId()).thenReturn(UUID.randomUUID());
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
                                return controller.setup(
                                        new AuthCredentials("second", "test-password"));
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
            assertEquals(1, sessions.activeSessionCount());
        } finally {
            release.countDown();
        }
    }

    @Test
    void passwordResetCannotInterleaveAuthenticationAndTokenIssuance() throws Exception {
        var users = mock(UserRegistry.class);
        var lifecycle = mock(AuthLifecycleManager.class);
        var sessions = new SessionManager();
        var controller = new AuthController(users, sessions, mock(KeysteadVault.class), lifecycle);
        var userId = UUID.randomUUID();
        var user = mock(UserEntity.class);
        when(user.getUserId()).thenReturn(userId);
        when(user.getRole()).thenReturn(UserRegistry.Role.USER);
        var authenticating = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var resetting = new CountDownLatch(1);
        var resetEntered = new CountDownLatch(1);
        when(users.authenticate("owner", "test-password"))
                .thenAnswer(
                        invocation -> {
                            authenticating.countDown();
                            assertTrue(release.await(5, TimeUnit.SECONDS));
                            return Optional.of(user);
                        });
        doAnswer(
                        invocation -> {
                            resetEntered.countDown();
                            sessions.invalidateUser(userId);
                            return null;
                        })
                .when(lifecycle)
                .logout(userId);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var login =
                    pool.submit(
                            () -> controller.login(new AuthCredentials("owner", "test-password")));
            assertTrue(authenticating.await(5, TimeUnit.SECONDS));
            var reset =
                    pool.submit(
                            () -> {
                                resetting.countDown();
                                synchronized (lifecycle) {
                                    lifecycle.logout(userId);
                                }
                            });
            assertTrue(resetting.await(5, TimeUnit.SECONDS));
            try {
                assertFalse(
                        resetEntered.await(200, TimeUnit.MILLISECONDS),
                        "Reset must not revoke between password verification and token creation");
            } finally {
                release.countDown();
            }
            assertEquals(200, login.get(5, TimeUnit.SECONDS).getStatusCode().value());
            reset.get(5, TimeUnit.SECONDS);
            assertFalse(sessions.hasSessions(userId));
        } finally {
            release.countDown();
        }
    }

    @Test
    void lastOwnerTokenLogoutClosesItsVaultWhileAnotherOwnerRemainsLoggedIn() throws Exception {
        var sessions = new SessionManager();
        var aliceId = UUID.randomUUID();
        var aliceToken = sessions.createSession(aliceId, "alice");
        var bobToken = sessions.createSession(UUID.randomUUID(), "bob");
        var lifecycle = mock(AuthLifecycleManager.class);
        var mvc =
                MockMvcBuilders.standaloneSetup(
                                new AuthController(
                                        mock(UserRegistry.class),
                                        sessions,
                                        mock(KeysteadVault.class),
                                        lifecycle))
                        .build();
        mvc.perform(post("/api/auth/logout").header("X-Veto-Session-Token", aliceToken))
                .andExpect(status().isOk());
        verify(lifecycle).logout(aliceId);
        assertTrue(sessions.validate(bobToken).isPresent());
    }

    @Test
    void invalidRegistrationNeverCreatesAUserOrVault() throws Exception {
        UserRegistry users = mock(UserRegistry.class);
        SessionManager sessions = mock(SessionManager.class);
        KeysteadVault vault = mock(KeysteadVault.class);
        AuthLifecycleManager lifecycle = mock(AuthLifecycleManager.class);
        var mvc =
                MockMvcBuilders.standaloneSetup(
                                new AuthController(users, sessions, vault, lifecycle))
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
}
