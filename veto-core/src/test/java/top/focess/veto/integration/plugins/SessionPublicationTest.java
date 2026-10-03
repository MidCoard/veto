package top.focess.veto.integration.plugins;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import top.focess.veto.api.agent.control.SourceEvidence;
import top.focess.veto.api.llm.VetoResponse;
import top.focess.veto.api.plugin.contract.AgentInbox;
import top.focess.veto.api.plugin.contract.ModelResponsePolicy;
import top.focess.veto.api.plugin.contract.StandardContributionPoints;
import top.focess.veto.api.plugin.contribution.Contribution;

class SessionPublicationTest {
    @Test
    void inboxUsesPreparedRoutesAndRechecksSelectionBeforeAcknowledgement() throws Exception {
        var inbox = mock(AgentInbox.class);
        var raw =
                new AgentInbox.Observation(
                        "local", null, "work", Instant.now(), "topic", Map.of(), null);
        when(inbox.pending(any())).thenReturn(List.of(raw));
        try (var fixture =
                new WorkflowPluginFixture(
                        List.of(
                                Contribution.of(
                                        StandardContributionPoints.AGENT_INBOX, "inbox", inbox)))) {
            var publication = fixture.manager.registry();
            doThrow(new AssertionError("Runtime contribution discovery"))
                    .when(publication)
                    .entries(StandardContributionPoints.AGENT_INBOX);
            var source = fixture.sessions.workSource("session");
            var scope = new AgentInbox.InboxContext("session", "agent", null);
            var value = source.pending(scope).getFirst();
            fixture.useUnselectedSession();
            assertTrue(source.pending(scope).isEmpty());
            assertThrows(IllegalStateException.class, () -> source.started(scope, value));
            verify(inbox, never()).started(any(), any());
            fixture.restoreSelectedSession();
            source.started(scope, value);
            verify(inbox).started(eq(scope), any());
        }
    }

    @Test
    void policyAndLazyInboxUseCapturedOwnersAndRetainLiveAdmission() throws Exception {
        var policy = mock(ModelResponsePolicy.class);
        var exchange = mock(ModelResponsePolicy.Exchange.class);
        when(policy.open()).thenReturn(exchange);
        var inbox = mock(AgentInbox.class);
        when(inbox.pending(any())).thenReturn(List.of());
        try (var fixture =
                new WorkflowPluginFixture(
                        List.of(
                                Contribution.of(
                                        StandardContributionPoints.MODEL_RESPONSE,
                                        "policy",
                                        policy),
                                Contribution.of(
                                        StandardContributionPoints.AGENT_INBOX, "inbox", inbox)))) {
            var publication = fixture.manager.registry();
            doThrow(new AssertionError("Runtime inbox contribution discovery"))
                    .when(publication)
                    .entries(StandardContributionPoints.AGENT_INBOX);
            var policies = fixture.sessions.responsePolicies("session");
            assertEquals(1, policies.size());
            verify(policy).open();
            var source = fixture.sessions.workSource("session");
            var scope = new AgentInbox.InboxContext("session", "agent", "request");
            assertTrue(source.pending(scope).isEmpty());
            verify(inbox).pending(scope);
            fixture.runtime.close();
            assertThrows(
                    IllegalStateException.class,
                    () ->
                            policies.getFirst()
                                    .check(
                                            new VetoResponse("done", List.of(), "done"),
                                            mock(SourceEvidence.class)));
            assertTrue(source.pending(scope).isEmpty());
            verifyNoMoreInteractions(inbox);
        }
    }
}
