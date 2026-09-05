package top.focess.veto.controller;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import top.focess.veto.agent.tool.ToolDocs;
import top.focess.veto.session.SessionService;
import top.focess.veto.vault.KeysteadVault;
import top.focess.veto.vault.UserContext;

class RequestAuthorizationTest {

    @AfterEach
    void clearUserContext() {
        UserContext.clear();
    }

    @Test
    void sessionAccessRequiresUnlockedVaultAndUsesCurrentOwner() {
        KeysteadVault vault = mock(ToolDocs.nonNullClass(KeysteadVault.class));
        SessionService sessions = mock(ToolDocs.nonNullClass(SessionService.class));
        UserContext.set("alice");
        assertEquals(
                HttpStatus.UNAUTHORIZED,
                assertThrows(
                                ResponseStatusException.class,
                                () ->
                                        RequestAuthorization.requireAgentId(
                                                "shared-name", sessions, vault))
                        .getStatusCode());
        verifyNoInteractions(sessions);
        when(vault.currentUser()).thenReturn("alice");
        when(sessions.primaryAgentIdFor("shared-name", "alice")).thenReturn(Optional.empty());
        assertEquals(
                HttpStatus.NOT_FOUND,
                assertThrows(
                                ResponseStatusException.class,
                                () ->
                                        RequestAuthorization.requireAgentId(
                                                "shared-name", sessions, vault))
                        .getStatusCode());
        when(sessions.primaryAgentIdFor("shared-name", "alice"))
                .thenReturn(Optional.of("alice-agent"));
        assertEquals(
                "alice-agent", RequestAuthorization.requireAgentId("shared-name", sessions, vault));
    }

    @Test
    void requestMustBeAuthenticated() {
        RequestAuthorization authorization = new RequestAuthorization(name -> true);

        ResponseStatusException error =
                assertThrows(ResponseStatusException.class, authorization::requireAdmin);

        assertEquals(HttpStatus.UNAUTHORIZED, error.getStatusCode());
    }

    @Test
    void authenticatedUserMustBeAdministrator() {
        UserContext.set("member");
        RequestAuthorization authorization = new RequestAuthorization(name -> false);

        ResponseStatusException error =
                assertThrows(ResponseStatusException.class, authorization::requireAdmin);

        assertEquals(HttpStatus.FORBIDDEN, error.getStatusCode());
    }

    @Test
    void authenticatedAdministratorPasses() {
        UserContext.set("admin");
        RequestAuthorization authorization = new RequestAuthorization("admin"::equals);

        assertDoesNotThrow(authorization::requireAdmin);
    }
}
