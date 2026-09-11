package top.focess.veto.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import top.focess.veto.agent.SessionAgentRegistry;
import top.focess.veto.llm.core.ProviderType;
import top.focess.veto.llm.core.ToolResultPresentationMode;
import top.focess.veto.session.LlmConfig;
import top.focess.veto.session.SessionService;
import top.focess.veto.vault.UserContext;

class AgentControlControllerTest {
    @AfterEach
    void clearIdentity() {
        UserContext.clear();
    }

    @ParameterizedTest
    @ValueSource(strings = {"pause", "resume"})
    void controlsOnlyTheAuthenticatedSession(@NonNull String operation) {
        @NonNull SessionService sessions = mock();
        @NonNull SessionAgentRegistry agents = mock();
        var controller = new AgentControlController(sessions, agents);
        UUID id = UUID.randomUUID();
        UserContext.set("owner");
        when(sessions.resolveByName("owned", "owner"))
                .thenReturn(
                        Optional.of(
                                new SessionService.SessionConfig(
                                        id.toString(),
                                        new LlmConfig(ProviderType.DEEPSEEK, "model", "key"),
                                        ToolResultPresentationMode.BASIC,
                                        false)));
        assertEquals(
                operation.equals("pause"),
                controller.control("owned", "agent", operation).get("userPaused"));
        verify(agents).controlPause(id, "agent", operation.equals("pause"));
        assertEquals(
                HttpStatus.NOT_FOUND,
                assertThrows(
                                ResponseStatusException.class,
                                () -> controller.control("other", "agent", operation))
                        .getStatusCode());
        verifyNoMoreInteractions(agents);
    }

    @Test
    void anonymousAndUnknownOperationsCannotControlAnAgent() {
        @NonNull SessionService sessions = mock();
        @NonNull SessionAgentRegistry agents = mock();
        var controller = new AgentControlController(sessions, agents);
        assertEquals(
                HttpStatus.UNAUTHORIZED,
                assertThrows(
                                ResponseStatusException.class,
                                () -> controller.control("owned", "agent", "pause"))
                        .getStatusCode());
        UserContext.set("owner");
        assertEquals(
                HttpStatus.BAD_REQUEST,
                assertThrows(
                                ResponseStatusException.class,
                                () -> controller.control("owned", "agent", "approve"))
                        .getStatusCode());
        verifyNoInteractions(sessions, agents);
    }
}
