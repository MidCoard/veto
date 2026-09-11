package top.focess.veto.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.veto.agent.AgentService;
import top.focess.veto.agent.ProtectedInputException;
import top.focess.veto.controller.dto.SubmitPromptRequest;
import top.focess.veto.llm.core.ProviderType;
import top.focess.veto.llm.core.ToolResultPresentationMode;
import top.focess.veto.session.LlmConfig;
import top.focess.veto.session.SessionService;
import top.focess.veto.vault.KeysteadVault;

class PromptControllerTest {
    @Test
    void rejectedProtectedInputDoesNotReceiveAnAcceptedAcknowledgement() {
        @NonNull SessionService sessions = mock();
        @NonNull AgentService agents = mock();
        @NonNull KeysteadVault vault = mock();
        when(vault.currentUser()).thenReturn("owner");
        when(sessions.activateForRest("session", "owner"))
                .thenReturn(
                        Optional.of(
                                new SessionService.SessionConfig(
                                        UUID.randomUUID().toString(),
                                        new LlmConfig(ProviderType.DEEPSEEK, "model", "key"),
                                        ToolResultPresentationMode.BASIC,
                                        false)));
        doThrow(new ProtectedInputException())
                .when(agents)
                .submitNow(anyString(), anyString(), any());
        var response =
                new PromptController(sessions, agents, vault)
                        .prompt("session", new SubmitPromptRequest("synthetic-secret"));
        if (response == null) throw new AssertionError("Missing rejection response");
        assertEquals(422, response.getStatusCode().value());
        if (!(response.getBody() instanceof Map<?, ?> body))
            throw new AssertionError("Missing error body");
        assertEquals("PROTECTED_INPUT_UNAVAILABLE", body.get("code"));
        assertFalse(body.toString().contains("synthetic-secret"));
    }
}
