package top.focess.veto.agent;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.veto.api.plugin.contract.AgentInbox;
import top.focess.veto.integration.plugins.SessionPlugins;
import top.focess.veto.vault.TestUsers;

class AgentContinuationExecutionTest {
    @Test
    void lazyInboxIsRetainedByThisRunnerAndChangesOnlyWithSelectionServiceIdentity() {
        var session = UUID.randomUUID();
        var first = mock(SessionPlugins.class);
        var second = mock(SessionPlugins.class);
        var firstInbox = mock(AgentInbox.class);
        var secondInbox = mock(AgentInbox.class);
        when(first.workSource(session.toString())).thenReturn(firstInbox);
        when(second.workSource(session.toString())).thenReturn(secondInbox);
        var selected = new AtomicReference<@NonNull SessionPlugins>(first);
        var continuation =
                new AgentContinuationExecution(
                        "agent",
                        session,
                        TestUsers.OWNER,
                        5,
                        mock(AgentOutput.class),
                        new LinkedBlockingQueue<>(),
                        selected::get);
        assertSame(firstInbox, required(continuation.source()));
        assertSame(firstInbox, required(continuation.source()));
        verify(first, times(1)).workSource(session.toString());
        selected.set(second);
        assertSame(secondInbox, required(continuation.source()));
        assertSame(secondInbox, required(continuation.source()));
        verify(second, times(1)).workSource(session.toString());
        var attached = mock(AgentInbox.class);
        continuation.attachWorkSource(attached);
        assertSame(attached, required(continuation.source()));
        verifyNoMoreInteractions(first, second);
    }

    private static @NonNull AgentInbox required(AgentInbox inbox) {
        if (inbox == null) throw new AssertionError("Expected the configured inbox");
        return inbox;
    }
}
