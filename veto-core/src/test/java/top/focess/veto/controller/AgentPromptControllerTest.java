package top.focess.veto.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import top.focess.veto.agent.AgentState;
import top.focess.veto.agent.SessionAgentRegistry;
import top.focess.veto.agent.VetoAgent;
import top.focess.veto.controller.dto.SubmitPromptRequest;
import top.focess.veto.llm.core.ProviderType;
import top.focess.veto.llm.core.ToolResultPresentationMode;
import top.focess.veto.session.LlmConfig;
import top.focess.veto.session.SessionService;
import top.focess.veto.vault.KeysteadVault;

class AgentPromptControllerTest {
    private final @NonNull SessionService sessions = mock();
    private final @NonNull SessionAgentRegistry agents = mock();
    private final @NonNull KeysteadVault vault = mock();
    private final @NonNull VetoAgent mate = mock();
    private final @NonNull UUID sessionId = UUID.randomUUID();
    private final @NonNull AgentPromptController controller =
            new AgentPromptController(sessions, agents, vault);

    private void ownedSession() {
        when(vault.currentUser()).thenReturn("owner");
        when(sessions.resolveByName("session", "owner"))
                .thenReturn(
                        Optional.of(
                                new SessionService.SessionConfig(
                                        sessionId.toString(),
                                        new LlmConfig(ProviderType.DEEPSEEK, "model", "key"),
                                        ToolResultPresentationMode.BASIC,
                                        false)));
        when(mate.id()).thenReturn("mate");
        when(mate.state()).thenReturn(AgentState.RUNNING);
        when(agents.agents(sessionId))
                .thenReturn(List.of(new SessionAgentRegistry.Entry(sessionId, null, null, mate)));
    }

    @Test
    void queuesForEnabledMateEvenWhileItIsWorking() {
        ownedSession();
        when(mate.userInteractionEnabled()).thenReturn(true);
        assertEquals(
                HttpStatus.ACCEPTED,
                controller
                        .prompt("session", "mate", new SubmitPromptRequest("Review"))
                        .getStatusCode());
        verify(mate).submitUserPrompt("Review");
        verify(mate, never()).submit(anyString());
    }

    @Test
    void rejectsDisabledTerminatedAndMissingAgents() {
        ownedSession();
        assertEquals(
                HttpStatus.FORBIDDEN,
                controller
                        .prompt("session", "mate", new SubmitPromptRequest("Review"))
                        .getStatusCode());
        when(mate.userInteractionEnabled()).thenReturn(true);
        when(mate.state()).thenReturn(AgentState.TERMINATED);
        assertEquals(
                HttpStatus.CONFLICT,
                controller
                        .prompt("session", "mate", new SubmitPromptRequest("Review"))
                        .getStatusCode());
        var missing =
                assertThrows(
                        ResponseStatusException.class,
                        () ->
                                controller.prompt(
                                        "session",
                                        "other-session-agent",
                                        new SubmitPromptRequest("Review")));
        assertEquals(HttpStatus.NOT_FOUND, missing.getStatusCode());
        verify(mate, never()).submitUserPrompt(anyString());
    }

    @Test
    void authenticatesAndChecksSessionOwnershipBeforeLookingUpAnAgent() {
        var unauthenticated =
                assertThrows(
                        ResponseStatusException.class,
                        () ->
                                controller.prompt(
                                        "session", "mate", new SubmitPromptRequest("Review")));
        assertEquals(HttpStatus.UNAUTHORIZED, unauthenticated.getStatusCode());
        when(vault.currentUser()).thenReturn("other-owner");
        when(sessions.resolveByName("session", "other-owner")).thenReturn(Optional.empty());
        var missing =
                assertThrows(
                        ResponseStatusException.class,
                        () ->
                                controller.prompt(
                                        "session", "mate", new SubmitPromptRequest("Review")));
        assertEquals(HttpStatus.NOT_FOUND, missing.getStatusCode());
        verifyNoInteractions(agents);
    }

    @Test
    void rejectsBlankPrompts() {
        ownedSession();
        assertEquals(
                HttpStatus.BAD_REQUEST,
                controller
                        .prompt("session", "mate", new SubmitPromptRequest("  "))
                        .getStatusCode());
        verify(mate, never()).submitUserPrompt(anyString());
    }
}
