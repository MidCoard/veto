package top.focess.veto.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import top.focess.veto.agent.AgentProfiles;
import top.focess.veto.agent.AgentService;
import top.focess.veto.api.agent.AgentResult;
import top.focess.veto.api.llm.LlmBinding;
import top.focess.veto.api.llm.ProviderType;
import top.focess.veto.api.llm.ToolResultPresentationMode;
import top.focess.veto.api.plugin.agent.AgentProfile;
import top.focess.veto.bus.DeltaBroker;
import top.focess.veto.command.PromptHandler;
import top.focess.veto.command.SessionCommandService;
import top.focess.veto.command.VetoCommandSender;
import top.focess.veto.contract.EventFrame;
import top.focess.veto.contract.Frame;
import top.focess.veto.controller.dto.SubmitPromptRequest;
import top.focess.veto.model.tier.ModelBinding;
import top.focess.veto.model.tier.ModelTier;
import top.focess.veto.model.tier.ModelTierRegistry;
import top.focess.veto.session.LlmConfig;
import top.focess.veto.session.SessionService;
import top.focess.veto.vault.KeysteadVault;
import top.focess.veto.vault.TestUsers;

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
        SessionService sessions = mock(SessionService.class);
        AgentService agents = mock(AgentService.class);
        KeysteadVault vault = mock(KeysteadVault.class);
        when(vault.currentUser()).thenReturn(TestUsers.OWNER);
        when(sessions.activateForRest("session", TestUsers.OWNER))
                .thenReturn(
                        Optional.of(
                                new SessionService.SessionConfig(
                                        "00000000-0000-0000-0000-000000000001",
                                        config,
                                        ToolResultPresentationMode.BASIC)));
        var response =
                new PromptController(sessions, agents, vault, mock(SessionCommandService.class))
                        .prompt("session", new SubmitPromptRequest("Explain TCP"));
        if (response == null) {
            throw new AssertionError("Prompt submission must return an HTTP response");
        }
        assertEquals(202, response.getStatusCode().value());
        var binding = ArgumentCaptor.forClass(LlmBinding.class);
        verify(agents)
                .submitNow(
                        eq("00000000-0000-0000-0000-000000000001"),
                        eq("Explain TCP"),
                        binding.capture(),
                        eq(TestUsers.OWNER));
        assertEquals(model.llmOptions(), binding.getValue().options());
        assertEquals(model.model(), binding.getValue().model());
        assertEquals(model.baseUrl(), binding.getValue().baseUrl());
    }

    @Test
    void terminalSubmissionKeepsTheResolvedTierOptions() throws Exception {
        SessionService sessions = mock(SessionService.class);
        AgentService agents = mock(AgentService.class);
        KeysteadVault vault = mock(KeysteadVault.class);
        VetoCommandSender sender = mock(VetoCommandSender.class);
        when(sender.userId()).thenReturn(TestUsers.OWNER);
        when(vault.isUnlocked(TestUsers.OWNER)).thenReturn(true);
        when(sessions.resolveLlmConfig("terminal")).thenReturn(Optional.of(config));
        when(sessions.activeSession("terminal"))
                .thenReturn(Optional.of("00000000-0000-0000-0000-000000000001"));
        when(agents.submit(anyString(), anyString(), any(), any(), isNull(), any(), any()))
                .thenReturn(AgentResult.success("done", Map.of()));
        new PromptHandler(vault, agents, sessions, new DeltaBroker())
                .handle("Explain TCP", "terminal", sender);
        var binding = ArgumentCaptor.forClass(LlmBinding.class);
        verify(agents)
                .submit(
                        eq("00000000-0000-0000-0000-000000000001"),
                        eq("Explain TCP"),
                        binding.capture(),
                        any(),
                        isNull(),
                        any(),
                        eq(TestUsers.OWNER));
        assertEquals(model.llmOptions(), binding.getValue().options());
    }

    @Test
    void terminalForwardsCanonicalEventsAndDetachesAfterFailure() throws Exception {
        var sessions = mock(SessionService.class);
        var agents = mock(AgentService.class);
        var vault = mock(KeysteadVault.class);
        var sender = mock(VetoCommandSender.class);
        var broker = new DeltaBroker();
        var id = UUID.fromString("00000000-0000-0000-0000-000000000001");
        when(sender.userId()).thenReturn(TestUsers.OWNER);
        when(vault.isUnlocked(TestUsers.OWNER)).thenReturn(true);
        when(sessions.resolveLlmConfig("terminal")).thenReturn(Optional.of(config));
        when(sessions.activeSession("terminal")).thenReturn(Optional.of(id.toString()));
        var frame =
                new EventFrame(
                        id,
                        0,
                        Instant.parse("2026-10-10T00:00:00Z"),
                        EventFrame.Kind.TOOL_CALL,
                        "tool",
                        Map.of());
        when(agents.submit(anyString(), anyString(), any(), any(), isNull(), any(), any()))
                .thenAnswer(
                        invocation -> {
                            broker.publish(frame);
                            throw new TimeoutException("test");
                        });
        var result =
                new PromptHandler(vault, agents, sessions, broker)
                        .handle("prompt", "terminal", sender);
        assertInstanceOf(Frame.Error.class, result);
        var delivered = ArgumentCaptor.forClass(EventFrame.class);
        verify(sender).sendEvent(delivered.capture());
        var event = delivered.getValue();
        assertEquals(id, event.sessionId());
        assertEquals(1, event.sequence());
        assertEquals(frame.emittedAt(), event.emittedAt());
        assertEquals(frame.kind(), event.kind());
        assertEquals(frame.text(), event.text());
        broker.publish(frame);
        verify(sender, times(1)).sendEvent(any());
    }

    @Test
    void leaderKeepsConfiguredWindowAndOutputReservation() {
        ModelTierRegistry tiers = mock(ModelTierRegistry.class);
        when(tiers.resolve(TestUsers.OWNER, ModelTier.TOP)).thenReturn(model);
        var leader =
                AgentProfiles.resolve(
                                "agent",
                                TestUsers.OWNER,
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
