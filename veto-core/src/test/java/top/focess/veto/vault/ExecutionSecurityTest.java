package top.focess.veto.vault;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.concurrent.DelegatingSecurityContextCallable;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class ExecutionSecurityTest {
    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @SuppressWarnings("try") // WHY: the owned identity scope exists solely for its exit cleanup.
    void nestedOwnerScopeRestoresExactCallerAuthenticationAfterFailure() {
        var caller = ExecutionSecurity.contextFor(TestUsers.ADMIN);
        var authentication =
                UsernamePasswordAuthenticationToken.authenticated(
                        TestUsers.ADMIN, "", List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        caller.setAuthentication(authentication);
        SecurityContextHolder.setContext(caller);
        assertThrows(
                IllegalStateException.class,
                () -> {
                    try (var scope = ExecutionSecurity.open(TestUsers.ALICE)) {
                        assertEquals(TestUsers.ALICE, CurrentUser.id());
                        var owner = SecurityContextHolder.getContext().getAuthentication();
                        assertNotNull(owner);
                        assertTrue(owner.getAuthorities().isEmpty());
                        assertNull(owner.getCredentials());
                        try (var anonymous = ExecutionSecurity.open(null)) {
                            assertNull(CurrentUser.id());
                        }
                        assertEquals(TestUsers.ALICE, CurrentUser.id());
                        throw new IllegalStateException("task failed");
                    }
                });
        assertSame(caller, SecurityContextHolder.getContext());
        assertSame(authentication, SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    @SuppressWarnings("try") // WHY: the owned identity scope exists solely for its exit cleanup.
    void emptyCallerScopeIsClearedOnExit() {
        try (var scope = ExecutionSecurity.open(TestUsers.ALICE)) {
            assertEquals(TestUsers.ALICE, CurrentUser.id());
        }
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertNull(CurrentUser.id());
    }

    @Test
    void unsupportedAndAnonymousPrincipalsDoNotBecomeDomainUsers() {
        var context = SecurityContextHolder.createEmptyContext();
        SecurityContextHolder.setContext(context);
        context.setAuthentication(
                new AnonymousAuthenticationToken(
                        "key",
                        TestUsers.ALICE,
                        List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));
        // An anonymous token reports authenticated=true; UUID alone is insufficient.
        assertNull(CurrentUser.id());
        context.setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("alice", "", List.of()));
        assertNull(CurrentUser.id());
        context.setAuthentication(
                UsernamePasswordAuthenticationToken.unauthenticated(TestUsers.ALICE, ""));
        assertNull(CurrentUser.id());
    }

    @Test
    @SuppressWarnings("try") // WHY: the owned identity scope exists solely for its exit cleanup.
    void explicitOwnerCallableIgnoresSubmittingIdentityAndCleansWorkerAfterException()
            throws Exception {
        try (var pool = Executors.newSingleThreadExecutor();
                var caller = ExecutionSecurity.open(TestUsers.BOB)) {
            var task =
                    new DelegatingSecurityContextCallable<Void>(
                            () -> {
                                assertEquals(TestUsers.ALICE, CurrentUser.id());
                                throw new IllegalStateException("dispatch failed");
                            },
                            ExecutionSecurity.contextFor(TestUsers.ALICE));
            var future = pool.submit(task);
            var failure =
                    assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
            assertInstanceOf(IllegalStateException.class, failure.getCause());
            assertNull(pool.submit(CurrentUser::id).get(5, TimeUnit.SECONDS));
            assertEquals(TestUsers.BOB, CurrentUser.id());
        }
    }
}
