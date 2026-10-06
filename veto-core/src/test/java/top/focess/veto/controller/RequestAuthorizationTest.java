package top.focess.veto.controller;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;
import top.focess.veto.session.SessionService;
import top.focess.veto.vault.ExecutionSecurity;
import top.focess.veto.vault.KeysteadVault;

class RequestAuthorizationTest {
    private static final @NonNull UUID ALICE =
            UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final @NonNull UUID MEMBER =
            UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final @NonNull UUID ADMIN =
            UUID.fromString("33333333-3333-3333-3333-333333333333");

    @AfterEach
    void clearCurrentUser() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void sessionAccessRequiresUnlockedVaultAndUsesCurrentOwner() {
        KeysteadVault vault = mock(KeysteadVault.class);
        SessionService sessions = mock(SessionService.class);
        SecurityContextHolder.setContext(ExecutionSecurity.contextFor(ALICE));
        assertEquals(
                HttpStatus.UNAUTHORIZED,
                assertThrows(
                                ResponseStatusException.class,
                                () ->
                                        RequestAuthorization.requireAgentId(
                                                "shared-name", sessions, vault))
                        .getStatusCode());
        verifyNoInteractions(sessions);
        when(vault.currentUser()).thenReturn(ALICE);
        when(sessions.primaryAgentIdFor("shared-name", ALICE)).thenReturn(Optional.empty());
        assertEquals(
                HttpStatus.NOT_FOUND,
                assertThrows(
                                ResponseStatusException.class,
                                () ->
                                        RequestAuthorization.requireAgentId(
                                                "shared-name", sessions, vault))
                        .getStatusCode());
        when(sessions.primaryAgentIdFor("shared-name", ALICE))
                .thenReturn(Optional.of("alice-agent"));
        assertEquals(
                "alice-agent", RequestAuthorization.requireAgentId("shared-name", sessions, vault));
    }

    @Test
    void requestMustBeAuthenticated() {
        RequestAuthorization authorization = AuthorizationTestSupport.authorizer(name -> true);

        ResponseStatusException error =
                assertThrows(ResponseStatusException.class, authorization::requireAdmin);

        assertEquals(HttpStatus.UNAUTHORIZED, error.getStatusCode());
    }

    @Test
    void authenticatedUserMustBeAdministrator() {
        SecurityContextHolder.setContext(ExecutionSecurity.contextFor(MEMBER));
        RequestAuthorization authorization = AuthorizationTestSupport.authorizer(name -> false);

        ResponseStatusException error =
                assertThrows(ResponseStatusException.class, authorization::requireAdmin);

        assertEquals(HttpStatus.FORBIDDEN, error.getStatusCode());
    }

    @Test
    void authenticatedAdministratorPasses() {
        SecurityContextHolder.setContext(ExecutionSecurity.contextFor(ADMIN));
        RequestAuthorization authorization = AuthorizationTestSupport.authorizer(ADMIN::equals);

        assertDoesNotThrow(authorization::requireAdmin);
    }
}
