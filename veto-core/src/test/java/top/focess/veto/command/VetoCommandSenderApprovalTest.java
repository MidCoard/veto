package top.focess.veto.command;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import top.focess.veto.agent.AgentService;
import top.focess.veto.api.plugin.Scope;
import top.focess.veto.contract.Frame;
import top.focess.veto.contract.Version;
import top.focess.veto.model.SessionEntity;
import top.focess.veto.model.SessionRepository;
import top.focess.veto.session.ProtocolConnection;
import top.focess.veto.session.SessionService;
import top.focess.veto.session.TestAgentSessions;
import top.focess.veto.vault.TestUsers;

/** Approval identity and attachment belong to the real connection, not its command adapter. */
class VetoCommandSenderApprovalTest {
    @Test
    void concurrentApprovalsResolveOnlyTheirExactIdentityAndRejectDetachedReplies()
            throws Exception {
        var agents = mock(AgentService.class);
        var repository = mock(SessionRepository.class);
        when(repository.findById(anyString()))
                .thenReturn(Optional.of(new SessionEntity(TestUsers.ALICE, "owned")));
        when(agents.hasApprovalAgent(anyString(), eq("agent"))).thenReturn(true);
        when(agents.resolveVeto("agent", "first", "ACCEPT_GENERIC", 1)).thenReturn(true);
        when(agents.resolveVeto("agent", "second", "ACCEPT_GENERIC", 2)).thenReturn(true);
        var runtime = TestAgentSessions.create(repository, agents);
        var frames = new LinkedBlockingQueue<Frame.ServerFrame>();
        var scope = new Scope.AgentScope(TestUsers.ALICE, UUID.randomUUID().toString(), "agent");
        try (var connection =
                new ProtocolConnection(
                        "terminal",
                        runtime,
                        mock(SessionService.class),
                        mock(SessionCommandService.class),
                        mock(CommandRegistry.class),
                        TestUsers.ALICE,
                        () -> true,
                        BooleanSupplier::getAsBoolean,
                        (frame, version, attached) -> {
                            frames.add(frame);
                            return true;
                        },
                        ignored -> {},
                        Locale.ENGLISH)) {
            connection.receive(new Frame.Hello(Frame.PROTOCOL_VERSION, 1, Version.UNKNOWN, "."));
            assertInstanceOf(Frame.Welcome.class, frames.poll(2, TimeUnit.SECONDS));
            runtime.select(
                    connection.id(), new Scope.SessionScope(TestUsers.ALICE, scope.session()));
            connection.receive(new Frame.VetoResult(scope, "first", "ACCEPT_GENERIC", 2));
            assertInstanceOf(Frame.Error.class, frames.poll(2, TimeUnit.SECONDS));
            verify(agents).resolveVeto("agent", "first", "ACCEPT_GENERIC", 2);
            connection.receive(new Frame.VetoResult(scope, "second", "ACCEPT_GENERIC", 2));
            connection.receive(new Frame.VetoResult(scope, "first", "ACCEPT_GENERIC", 1));
            verify(agents, timeout(2_000)).resolveVeto("agent", "second", "ACCEPT_GENERIC", 2);
            verify(agents, timeout(2_000)).resolveVeto("agent", "first", "ACCEPT_GENERIC", 1);
            // A remotely resolved approval is rejected by the canonical agent registry.
            when(agents.resolveVeto("agent", "first", "ACCEPT_GENERIC", 1)).thenReturn(false);
            connection.receive(new Frame.VetoResult(scope, "first", "ACCEPT_GENERIC", 1));
            assertInstanceOf(Frame.Error.class, frames.poll(2, TimeUnit.SECONDS));
            runtime.detach(connection.id());
            connection.receive(new Frame.VetoResult(scope, "second", "ACCEPT_GENERIC", 2));
            assertInstanceOf(Frame.Error.class, frames.poll(2, TimeUnit.SECONDS));
            verify(agents, times(1)).resolveVeto("agent", "second", "ACCEPT_GENERIC", 2);
        }
    }
}
