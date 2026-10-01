package top.focess.veto.integration.plugins;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import top.focess.veto.api.agent.control.SourceEvidence;
import top.focess.veto.api.llm.VetoResponse;
import top.focess.veto.api.plugin.contract.AgentInbox;
import top.focess.veto.api.plugin.contract.ModelResponsePolicy;
import top.focess.veto.api.plugin.contract.StandardContributionPoints;
import top.focess.veto.api.plugin.contribution.Contribution;

class SessionPublicationTest {
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
            // Mixing current-manager reads with captured entries would reach these traps.
            when(fixture.manager.plugin(anyString()))
                    .thenThrow(new IllegalStateException("A different manager generation"));
            when(fixture.manager.catalog())
                    .thenThrow(new IllegalStateException("A different manager generation"));
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
