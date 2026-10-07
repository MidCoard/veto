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
import top.focess.veto.session.SessionService.SessionConfig;
import top.focess.veto.vault.KeysteadVault;
import top.focess.veto.vault.TestUsers;

class QuoteCheckControllerTest {
    @Test
    void quotationTaskUsesMvcExecutorAndTenSecondTimeoutWithoutStartingWorkInController() {
        SessionService sessions = mock(SessionService.class);
        KeysteadVault vault = mock(KeysteadVault.class);
        QuoteCheckService quotes = mock(QuoteCheckService.class);
        var session = mock(SessionConfig.class);
        when(session.sessionId()).thenReturn("owned-session");
        when(vault.currentUser()).thenReturn(TestUsers.ALICE);
        when(sessions.resolveByName("private", TestUsers.ALICE)).thenReturn(Optional.of(session));
        var controller = new QuoteCheckController(sessions, vault, quotes);
        var task =
                controller.check(
                        "private", "agent", 2, new QuoteCheckController.Request("> quote"));
        var timeout = task.getTimeout();
        if (timeout == null) throw new AssertionError("Quotation timeout was not configured");
        assertEquals(10_000L, timeout);
        assertNull(task.getExecutor());
        verifyNoInteractions(quotes);
    }

    @Test
    void doesNotSearchWithoutAuthenticationOrSessionOwnership() {
        SessionService sessions = mock(SessionService.class);
        KeysteadVault vault = mock(KeysteadVault.class);
        QuoteCheckService quotes = mock(QuoteCheckService.class);
        var controller = new QuoteCheckController(sessions, vault, quotes);
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
    }

    @Test
    void doesNotReclassifyUnexpectedQuotationFailuresAsMissingOrChanged() {
        var sessions = mock(SessionService.class);
        var vault = mock(KeysteadVault.class);
        var quotes = mock(QuoteCheckService.class);
        var owned = mock(SessionConfig.class);
        when(owned.sessionId()).thenReturn("owned-session");
        when(vault.currentUser()).thenReturn(TestUsers.ALICE);
        when(sessions.resolveByName("private", TestUsers.ALICE)).thenReturn(Optional.of(owned));
        var failure = new IllegalArgumentException("Saved citations are not available");
        when(quotes.check("owned-session", "agent", 2, "answer")).thenThrow(failure);
        var task =
                new QuoteCheckController(sessions, vault, quotes)
                        .check("private", "agent", 2, new QuoteCheckController.Request("answer"));
        var callable = task.getCallable();
        if (callable == null) throw new AssertionError("Quotation callable was not configured");
        assertSame(failure, assertThrows(IllegalArgumentException.class, callable::call));
    }
}
