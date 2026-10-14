package top.focess.veto.command;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import top.focess.veto.agent.AgentService;
import top.focess.veto.contract.Delta;
import top.focess.veto.session.SessionService;
import top.focess.veto.session.TestAgentSessions;
import top.focess.veto.session.TestConnection;
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
                new SessionCommandService(mock(SessionService.class), TestAgentSessions.create());
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
                new SessionCommandService(mock(SessionService.class), TestAgentSessions.create());
        assertTrue(service.supports(input));
    }

    @Test
    void backendNoticeUsesTheAgentAndNeverEchoesPotentialCredentials() throws Exception {
        var runtime = TestAgentSessions.create();
        var sessions = mock(SessionService.class);
        var agents = mock(AgentService.class);
        TestAgentSessions.bind(sessions, agents);
        var service = new SessionCommandService(sessions, runtime);
        var id = UUID.randomUUID();
        List<@NonNull Delta> frames = new ArrayList<>();
        var connection =
                new TestConnection(
                        TestUsers.OWNER,
                        frame -> {
                            if (frame instanceof Delta delta) frames.add(delta);
                        });
        runtime.connectBrowser(connection.connection());
        try {
            service.notice(
                    TestUsers.OWNER,
                    id.toString(),
                    "child",
                    "/signup secret-password",
                    Locale.ENGLISH);
            service.notice(
                    TestUsers.OWNER, id.toString(), "child", "ordinary prompt", Locale.ENGLISH);
            assertEquals(1, frames.size());
            var frame = frames.getFirst();
            assertInstanceOf(Delta.Notice.class, frame.data());
            var agentId = frame.agentId();
            if (agentId == null) throw new AssertionError("Missing notice scope");
            assertEquals("child", agentId);
            assertFalse(((Delta.Notice) frame.data()).text().contains("secret-password"));
            assertTrue(((Delta.Notice) frame.data()).text().contains("message"));
        } finally {
            runtime.disconnect(connection.connection());
        }
        service.notice(TestUsers.OWNER, id.toString(), "child", "/unknown", Locale.ENGLISH);
        assertEquals(1, frames.size());
        verifyNoInteractions(sessions, agents);
    }
}
