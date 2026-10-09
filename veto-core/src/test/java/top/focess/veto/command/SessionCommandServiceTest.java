package top.focess.veto.command;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import top.focess.veto.agent.AgentService;
import top.focess.veto.agent.RequestHandle;
import top.focess.veto.agent.VetoAgent;
import top.focess.veto.bus.DeltaBroker;
import top.focess.veto.bus.DeltaFrame;
import top.focess.veto.session.SessionService;
import top.focess.veto.session.SessionService.SessionConfig;
import top.focess.veto.vault.TestUsers;

class SessionCommandServiceTest {
    @ParameterizedTest
    @ValueSource(
            strings = {
                "/signup",
                "/login",
                "/unknown",
                "/compact-extra",
                "/compacted",
                "/COMPACT",
                "compact",
                "hello /compact",
                "/",
                "",
                "   "
            })
    void nonSessionSyntaxIsNotACommand(@NonNull String input) {
        var service =
                new SessionCommandService(
                        mock(SessionService.class), mock(AgentService.class), new DeltaBroker());
        assertFalse(service.supports(input));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "/compact",
                "  /compact  ",
                "/compact extra",
                "/compact\textra",
                "/compact\nextra",
                "/compact \"quoted argument\"",
                "/compact 'unfinished",
                "/compact /login",
                "/\"compact\" extra"
            })
    void firstCommandTokenSelectsCompaction(@NonNull String input) {
        var service =
                new SessionCommandService(
                        mock(SessionService.class), mock(AgentService.class), new DeltaBroker());
        assertTrue(service.supports(input));
    }

    @Test
    void foreignSessionCannotEnqueueCompaction() {
        var sessions = mock(SessionService.class);
        var agents = mock(AgentService.class);
        when(sessions.activateForRest("foreign", TestUsers.OWNER)).thenReturn(Optional.empty());
        var service = new SessionCommandService(sessions, agents, new DeltaBroker());
        assertThrows(
                SessionCommandService.SessionNotFoundException.class,
                () -> service.enqueue("foreign", TestUsers.OWNER));
        verifyNoInteractions(agents);
    }

    @Test
    void enqueueReturnsWithoutWaitingForTheAgentResult() {
        var sessions = mock(SessionService.class);
        var agents = mock(AgentService.class);
        var owned = mock(SessionConfig.class);
        var agent = mock(VetoAgent.class);
        var request = mock(RequestHandle.class);
        String sessionId = UUID.randomUUID().toString();
        when(request.requestId()).thenReturn("queued-request");
        when(owned.sessionId()).thenReturn(sessionId);
        when(sessions.activateForRest("private", TestUsers.OWNER)).thenReturn(Optional.of(owned));
        when(agents.agentsView()).thenReturn(Map.of(sessionId, agent));
        when(agent.compact()).thenReturn(request);
        var service = new SessionCommandService(sessions, agents, new DeltaBroker());
        assertEquals(sessionId, service.enqueue("private", TestUsers.OWNER));
        verify(agent).compact();
        verify(request).requestId();
        verifyNoMoreInteractions(request);
    }

    @Test
    void backendNoticeNeedsNoAgentAndNeverEchoesPotentialCredentials() throws Exception {
        var broker = new DeltaBroker();
        var sessions = mock(SessionService.class);
        var agents = mock(AgentService.class);
        var service = new SessionCommandService(sessions, agents, broker);
        var id = UUID.randomUUID();
        List<@NonNull DeltaFrame> frames = new ArrayList<>();
        var subscription = broker.subscribe(id, frames::add);
        try {
            service.notice(id.toString(), "child", "/signup secret-password");
            service.notice(id.toString(), null, "ordinary prompt");
            assertEquals(1, frames.size());
            var frame = frames.getFirst();
            assertEquals(DeltaFrame.Kind.NOTICE, frame.kind());
            var agentId = frame.attrs().get("agentId");
            if (agentId == null) throw new AssertionError("Missing notice scope");
            assertEquals("child", agentId.asText());
            assertFalse(frame.text().contains("secret-password"));
            assertTrue(frame.text().contains("message"));
        } finally {
            subscription.close();
        }
        service.notice(id.toString(), "child", "/unknown");
        assertEquals(1, frames.size());
        verifyNoInteractions(sessions, agents);
    }
}
