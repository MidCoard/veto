package top.focess.veto.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import top.focess.veto.agent.AgentService;
import top.focess.veto.agent.ProtectedInputException;
import top.focess.veto.api.llm.ProviderType;
import top.focess.veto.api.llm.ToolResultPresentationMode;
import top.focess.veto.command.SessionCommandService;
import top.focess.veto.controller.dto.CodedErrorResponse;
import top.focess.veto.controller.dto.SubmitPromptRequest;
import top.focess.veto.session.LlmConfig;
import top.focess.veto.session.SessionService;
import top.focess.veto.vault.KeysteadVault;
import top.focess.veto.vault.TestUsers;

class PromptControllerTest {
    @Test
    void rejectedProtectedInputDoesNotReceiveAnAcceptedAcknowledgement() {
        SessionService sessions = mock(SessionService.class);
        AgentService agents = mock(AgentService.class);
        KeysteadVault vault = mock(KeysteadVault.class);
        when(vault.currentUser()).thenReturn(TestUsers.OWNER);
        when(sessions.activateForRest("session", TestUsers.OWNER))
                .thenReturn(
                        Optional.of(
                                new SessionService.SessionConfig(
                                        UUID.randomUUID().toString(),
                                        new LlmConfig(ProviderType.DEEPSEEK, "model", "key"),
                                        ToolResultPresentationMode.BASIC)));
        doThrow(new ProtectedInputException())
                .when(agents)
                .submitNow(anyString(), anyString(), any(), any());
        var response =
                new PromptController(sessions, agents, vault, mock(SessionCommandService.class))
                        .prompt("session", new SubmitPromptRequest("synthetic-secret"));
        if (response == null) throw new AssertionError("Missing rejection response");
        assertEquals(422, response.getStatusCode().value());
        if (!(response.getBody() instanceof CodedErrorResponse body))
            throw new AssertionError("Missing error body");
        assertEquals("PROTECTED_INPUT_UNAVAILABLE", body.code());
        assertFalse(String.valueOf(body).contains("synthetic-secret"));
    }
}
