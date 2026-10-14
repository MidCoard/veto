package top.focess.veto.command;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.AdditionalAnswers.answerVoid;
import static org.mockito.Mockito.*;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import top.focess.veto.agent.AgentProfiles;
import top.focess.veto.agent.AgentService;
import top.focess.veto.agent.RequestHandle;
import top.focess.veto.agent.VetoAgent;
import top.focess.veto.api.llm.LlmBinding;
import top.focess.veto.api.llm.ProviderType;
import top.focess.veto.api.plugin.Scope;
import top.focess.veto.api.plugin.agent.AgentProfile;
import top.focess.veto.contract.Frame;
import top.focess.veto.model.tier.ModelBinding;
import top.focess.veto.model.tier.ModelTier;
import top.focess.veto.model.tier.ModelTierRegistry;
import top.focess.veto.session.AgentSession;
import top.focess.veto.session.LlmConfig;
import top.focess.veto.session.ProtocolConnection;
import top.focess.veto.session.SessionService;
import top.focess.veto.session.TestAgentSessions;
import top.focess.veto.vault.KeysteadVault;
import top.focess.veto.vault.TestUsers;

class PromptBindingTest {
    private final @NonNull ModelBinding model =
            new ModelBinding(
                    ProviderType.ANTHROPIC,
                    "MiniMax-M3",
                    "reference",
                    0.2,
                    7000,
                    "https://example.test/anthropic",
                    96000);
    private final @NonNull LlmConfig config =
            new LlmConfig(
                    model.provider(),
                    model.model(),
                    model.credentialKey(),
                    model.baseUrl(),
                    model.llmOptions());

    @Test
    void terminalSubmissionKeepsTierOptionsAndCapturedLocaleWithoutWaiting() {
        var sessions = mock(SessionService.class);
        var agents = mock(AgentService.class);
        var agent = mock(VetoAgent.class);
        var request = mock(RequestHandle.class);
        when(agent.id()).thenReturn("primary");
        when(agents.agent(anyString())).thenReturn(agent);
        var runtime = TestAgentSessions.bind(sessions, agents);
        var vault = mock(KeysteadVault.class);
        var sender = mock(VetoCommandSender.class);
        var connection = mock(ProtocolConnection.class);
        when(sender.connection()).thenReturn(connection);
        when(connection.locale()).thenReturn(Locale.GERMAN);
        when(sender.userId()).thenReturn(TestUsers.OWNER);
        when(vault.isUnlocked(TestUsers.OWNER)).thenReturn(true);
        when(sessions.resolveLlmConfig("terminal")).thenReturn(Optional.of(config));
        String id = "00000000-0000-0000-0000-000000000001";
        when(sessions.activeSession("terminal")).thenReturn(Optional.of(id));
        when(agent.submitRequest(anyString(), any(), any(), any(), any())).thenReturn(request);
        doAnswer(
                        answerVoid(
                                (@NonNull AgentSession session,
                                        Scope.@NonNull AgentScope scope,
                                        @NonNull
                                                Function<
                                                        RequestHandle.@NonNull Admission,
                                                        @NonNull RequestHandle>
                                                submit) ->
                                        assertSame(
                                                request,
                                                submit.apply(RequestHandle.Admission.DIRECT))))
                .when(connection)
                .submitAgent(any(), any(), any());

        assertInstanceOf(
                Frame.Done.class,
                new PromptHandler(vault, agents, sessions)
                        .handle("Explain TCP", "terminal", sender));
        var binding = ArgumentCaptor.forClass(LlmBinding.class);
        verify(agent)
                .submitRequest(
                        eq("Explain TCP"),
                        binding.capture(),
                        eq(Locale.GERMAN),
                        isNull(),
                        eq(RequestHandle.Admission.DIRECT));
        assertEquals(model.llmOptions(), binding.getValue().options());
        assertEquals(model.model(), binding.getValue().model());
        assertEquals(model.baseUrl(), binding.getValue().baseUrl());
        verify(connection)
                .submitAgent(
                        eq(runtime.session(new Scope.SessionScope(TestUsers.OWNER, id))),
                        eq(new Scope.AgentScope(TestUsers.OWNER, id, "primary")),
                        any());
        verifyNoInteractions(request);
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
