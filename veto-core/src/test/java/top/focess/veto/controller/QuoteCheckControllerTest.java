package top.focess.veto.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Optional;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import top.focess.veto.session.QuoteCheckService;
import top.focess.veto.session.SessionService;
import top.focess.veto.vault.KeysteadVault;

class QuoteCheckControllerTest {
    @Test
    void doesNotSearchWithoutAuthenticationOrSessionOwnership() {
        @NonNull SessionService sessions = mock();
        @NonNull KeysteadVault vault = mock();
        @NonNull QuoteCheckService quotes = mock();
        var controller = new QuoteCheckController(sessions, vault, quotes);
        try {
            var body = new QuoteCheckController.Request("> quote");
            assertEquals(
                    HttpStatus.UNAUTHORIZED,
                    assertThrows(
                                    ResponseStatusException.class,
                                    () -> controller.check("private", "agent", 2, body))
                            .getStatusCode());
            when(vault.currentUser()).thenReturn("other-owner");
            when(sessions.resolveByName("private", "other-owner")).thenReturn(Optional.empty());
            assertEquals(
                    HttpStatus.NOT_FOUND,
                    assertThrows(
                                    ResponseStatusException.class,
                                    () -> controller.check("private", "agent", 2, body))
                            .getStatusCode());
            verifyNoInteractions(quotes);
        } finally {
            controller.close();
        }
    }
}
