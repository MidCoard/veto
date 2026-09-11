package top.focess.veto.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import top.focess.veto.llm.core.ProviderType;
import top.focess.veto.llm.core.ToolResultPresentationMode;
import top.focess.veto.monitor.MonitorRecord;
import top.focess.veto.monitor.MonitorService;
import top.focess.veto.session.LlmConfig;
import top.focess.veto.session.SessionService;
import top.focess.veto.vault.KeysteadVault;
import top.focess.veto.vault.UserContext;

class SessionMonitorControllerTest {
    private final @NonNull SessionService sessions = mock();
    private final @NonNull MonitorService monitors = mock();
    private final @NonNull KeysteadVault vault = mock();
    private final @NonNull SessionMonitorController controller =
            new SessionMonitorController(sessions, vault, monitors);

    @AfterEach
    void clearIdentity() {
        UserContext.clear();
    }

    private @NonNull MonitorRecord owned() {
        UserContext.set("owner");
        when(sessions.resolveByName("session", "owner"))
                .thenReturn(
                        Optional.of(
                                new SessionService.SessionConfig(
                                        "session-id",
                                        new LlmConfig(ProviderType.DEEPSEEK, "model", "key"),
                                        ToolResultPresentationMode.BASIC,
                                        false)));
        var record =
                new MonitorRecord(
                        "timer",
                        "owner",
                        "session-id",
                        "original-agent",
                        "TIME_ONCE",
                        "Reminder",
                        null,
                        null,
                        "ACTIVE",
                        Map.of(),
                        List.of(),
                        Instant.now());
        when(monitors.list("owner", "session-id")).thenReturn(List.of(record));
        return record;
    }

    @ParameterizedTest
    @ValueSource(strings = {"pause", "resume", "cancel"})
    void controlsTheStoredRecipientInTheAuthenticatedSession(@NonNull String operation) {
        var record = owned();
        when(monitors.control("owner", "session-id", "original-agent", "timer", operation))
                .thenReturn(record);
        assertSame(record, controller.control("session", "timer", operation));
        verify(monitors).control("owner", "session-id", "original-agent", "timer", operation);
        verifyNoInteractions(vault);
    }

    @Test
    void rejectsAnonymousEvenWhenAnotherVaultUserIsAvailable() {
        when(vault.currentUser()).thenReturn("other-user");
        assertEquals(
                HttpStatus.UNAUTHORIZED,
                assertThrows(
                                ResponseStatusException.class,
                                () -> controller.control("session", "timer", "cancel"))
                        .getStatusCode());
        verifyNoInteractions(sessions, monitors);
    }

    @Test
    void rejectsMissingSessionAndForeignRuleBeforeControl() {
        owned();
        when(sessions.resolveByName("foreign", "owner")).thenReturn(Optional.empty());
        assertEquals(
                HttpStatus.NOT_FOUND,
                assertThrows(
                                ResponseStatusException.class,
                                () -> controller.control("foreign", "timer", "cancel"))
                        .getStatusCode());
        assertEquals(
                HttpStatus.NOT_FOUND,
                assertThrows(
                                ResponseStatusException.class,
                                () -> controller.control("session", "foreign-timer", "cancel"))
                        .getStatusCode());
        verify(monitors, never())
                .control(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void rejectsUnknownOperationsAndUnsupportedLifecycleControl() {
        owned();
        assertEquals(
                HttpStatus.BAD_REQUEST,
                assertThrows(
                                ResponseStatusException.class,
                                () -> controller.control("session", "timer", "restart"))
                        .getStatusCode());
        when(monitors.control("owner", "session-id", "original-agent", "timer", "pause"))
                .thenThrow(new IllegalArgumentException("Group lifecycle"));
        assertEquals(
                HttpStatus.CONFLICT,
                assertThrows(
                                ResponseStatusException.class,
                                () -> controller.control("session", "timer", "pause"))
                        .getStatusCode());
    }
}
