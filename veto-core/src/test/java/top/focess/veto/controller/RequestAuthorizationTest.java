package top.focess.veto.controller;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import top.focess.veto.vault.UserContext;

class RequestAuthorizationTest {

    @AfterEach
    void clearUserContext() {
        UserContext.clear();
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
