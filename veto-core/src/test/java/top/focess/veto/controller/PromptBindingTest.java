package top.focess.veto.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import top.focess.veto.agent.AgentProfiles;
import top.focess.veto.agent.AgentService;
import top.focess.veto.api.agent.AgentResult;
import top.focess.veto.api.agent.tool.ToolDocs;
import top.focess.veto.api.llm.LlmBinding;
import top.focess.veto.api.llm.ProviderType;
import top.focess.veto.api.llm.ToolResultPresentationMode;
import top.focess.veto.api.plugin.agent.AgentProfile;
import top.focess.veto.command.PromptHandler;
import top.focess.veto.command.VetoCommandSender;
import top.focess.veto.controller.dto.SubmitPromptRequest;
import top.focess.veto.model.tier.ModelBinding;
import top.focess.veto.model.tier.ModelTier;
import top.focess.veto.model.tier.ModelTierRegistry;
import top.focess.veto.session.LlmConfig;
import top.focess.veto.session.SessionService;
import top.focess.veto.vault.KeysteadVault;

class PromptBindingTest {
    private final ModelBinding model =
            new ModelBinding(
                    ProviderType.ANTHROPIC,
                    "MiniMax-M3",
                    "reference",
                    0.2,
                    7000,
                    "https://example.test/anthropic",
                    96000);
    private final LlmConfig config =
            new LlmConfig(
                    model.provider(),
                    model.model(),
                    model.credentialKey(),
                    model.baseUrl(),
                    model.llmOptions());

    @Test
    void restSubmissionKeepsTheActivatedTierOptions() {
        @NonNull SessionService sessions = mock();
        @NonNull AgentService agents = mock();
        @NonNull KeysteadVault vault = mock();
        when(vault.currentUser()).thenReturn("owner");
        when(sessions.activateForRest("session", "owner"))
                .thenReturn(
                        Optional.of(
                                new SessionService.SessionConfig(
                                        "session-id", config, ToolResultPresentationMode.BASIC)));
        var response =
                new PromptController(sessions, agents, vault)
                        .prompt("session", new SubmitPromptRequest("Explain TCP"));
        if (response == null) {
            throw new AssertionError("Prompt submission must return an HTTP response");
        }
        assertEquals(202, response.getStatusCode().value());
        var binding = ArgumentCaptor.forClass(ToolDocs.nonNullClass(LlmBinding.class));
        verify(agents).submitNow(eq("session-id"), eq("Explain TCP"), binding.capture());
        assertEquals(model.llmOptions(), binding.getValue().options());
        assertEquals(model.model(), binding.getValue().model());
        assertEquals(model.baseUrl(), binding.getValue().baseUrl());
    }

    @Test
    void terminalSubmissionKeepsTheResolvedTierOptions() throws Exception {
        @NonNull SessionService sessions = mock();
        @NonNull AgentService agents = mock();
        @NonNull KeysteadVault vault = mock();
        @NonNull VetoCommandSender sender = mock();
        when(vault.currentUserOrOnlyUnlocked()).thenReturn("owner");
        when(sessions.resolveLlmConfig("terminal")).thenReturn(Optional.of(config));
        when(sessions.activeSession("terminal")).thenReturn(Optional.of("session-id"));
        when(agents.submit(
                        anyString(), anyString(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(AgentResult.success("done", Map.of()));
        new PromptHandler(vault, agents, sessions).handle("Explain TCP", "terminal", sender);
        var binding = ArgumentCaptor.forClass(ToolDocs.nonNullClass(LlmBinding.class));
        verify(agents)
                .submit(
                        eq("session-id"),
                        eq("Explain TCP"),
                        binding.capture(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any());
        assertEquals(model.llmOptions(), binding.getValue().options());
    }

    @Test
    void leaderKeepsConfiguredWindowAndOutputReservation() {
        @NonNull ModelTierRegistry tiers = mock();
        when(tiers.resolve("owner", ModelTier.TOP)).thenReturn(model);
        var leader =
                AgentProfiles.resolve(
                                "agent",
                                "owner",
                                new AgentProfile(
                                        "Leader", "", "LEADER", Set.of(), "TOP", null, Map.of()),
                                Set.of(),
                                new LlmBinding(
                                        model.provider(),
                                        model.model(),
                                        model.credentialKey(),
                                        model.llmOptions(),
                                        null),
                                tiers)
                        .binding();
        assertEquals(model.llmOptions(), leader.options());
        assertEquals(80100, leader.options().inputBudget());
    }
}
