package top.focess.veto.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import top.focess.veto.session.QuoteCheckService;
import top.focess.veto.session.SessionService;
import top.focess.veto.vault.KeysteadVault;

class QuoteCheckControllerTest {
    @Test
    void doesNotSearchWithoutAuthenticationOrSessionOwnership() {
        SessionService sessions = mock(SessionService.class);
        KeysteadVault vault = mock(KeysteadVault.class);
        QuoteCheckService quotes = mock(QuoteCheckService.class);
        var controller = new QuoteCheckController(sessions, vault, quotes);
        try {
            var body = new QuoteCheckController.Request("> quote");
            assertEquals(
                    HttpStatus.UNAUTHORIZED,
                    assertThrows(
                                    ResponseStatusException.class,
                                    () -> controller.check("private", "agent", 2, body))
                            .getStatusCode());
            when(vault.currentUser())
                    .thenReturn(UUID.fromString("b8e2a363-b485-59e6-af0d-96f11fcd35db"));
            when(sessions.resolveByName(
                            "private", UUID.fromString("b8e2a363-b485-59e6-af0d-96f11fcd35db")))
                    .thenReturn(Optional.empty());
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
