package top.focess.veto.agent.intercept;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import top.focess.veto.agent.screening.Relevance;
import top.focess.veto.agent.screening.Screening;
import top.focess.veto.agent.tool.NativeToolDefinition;
import top.focess.veto.agent.workspace.PathMode;
import top.focess.veto.agent.workspace.Workspace;
import top.focess.veto.api.agent.screening.Danger;
import top.focess.veto.api.agent.tool.ToolCapability;
import top.focess.veto.api.agent.tool.ToolDocs;
import top.focess.veto.llm.core.ToolCall;
import top.focess.veto.model.RetiredAgentControls;
import top.focess.veto.util.Nullness;

@DataJpaTest
@Import({HitlHistory.class, ObjectMapper.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@SuppressWarnings("initialization.field.uninitialized")
class HitlHistoryTest {
    @Autowired private @NonNull HitlHistory history;
    @Autowired private @NonNull HitlRecordRepository repository;
    @Autowired private @NonNull JdbcTemplate jdbc;

    @Test
    void networkSessionRuleSurvivesRestartWithoutBroadeningAndRevocationSurvivesToo(
            @TempDir @NonNull Path tmp) {
        UUID session = UUID.randomUUID();
        String agent = UUID.randomUUID().toString();
        var definition =
                new NativeToolDefinition(
                        "web_fetch",
                        "Read public page",
                        ToolCapability.NETWORK_EGRESS,
                        Danger.ELEVATED,
                        false,
                        Object.class,
                        ToolDocs.nonNullClass(Object.class),
                        Map.of());
        var call =
                new ToolCall(
                        "web_fetch",
                        Map.of("url", "https://example.com", "objective", "title"),
                        "first");
        var screening =
                new GatewayResult.Screened(
                        new Screening(
                                Relevance.HIGH,
                                Danger.DANGEROUS,
                                false,
                                VetoScenario.EXEC_FIRST_TIME,
                                "test"));
        var initial = new HitlRegistry();
        initial.attachHistory(history);
        initial.setSession(agent, session);
        try {
            var pending =
                    initial.register(
                            agent,
                            "first",
                            call,
                            definition,
                            List.of(VetoOption.ACCEPT_COMMAND_AS_SESSION_RULE),
                            Danger.DANGEROUS);
            assertTrue(initial.resolveOption(agent, "first", "ACCEPT_COMMAND_AS_SESSION_RULE"));
            assertTrue(pending.isDone());
            var restored = new HitlRegistry();
            restored.attachHistory(history);
            restored.setSession(agent, session);
            restored.setWorkspace(agent, Workspace.single(tmp, PathMode.REAL));
            assertInstanceOf(
                    ToolDocs.nonNullClass(ApprovalDecision.AutoApprove.class),
                    restored.decide(agent, call, definition, screening));
            var other =
                    new ToolCall(
                            "web_fetch",
                            Map.of("url", "https://example.org", "objective", "title"),
                            "other");
            assertInstanceOf(
                    ToolDocs.nonNullClass(ApprovalDecision.Prompt.class),
                    restored.decide(agent, other, definition, screening));
            assertTrue(restored.revokeGrant(agent, restored.grantLog(agent).getFirst()));
            var afterRevoke = new HitlRegistry();
            afterRevoke.attachHistory(history);
            afterRevoke.setSession(agent, session);
            afterRevoke.setWorkspace(agent, Workspace.single(tmp, PathMode.REAL));
            assertInstanceOf(
                    ToolDocs.nonNullClass(ApprovalDecision.Prompt.class),
                    afterRevoke.decide(agent, call, definition, screening));
        } finally {
            repository.deleteAll(
                    repository.findBySessionIdAndAgentIdOrderByIdAsc(session.toString(), agent));
        }
    }

    @Test
    void removesRetiredColumnsWithoutCopyingTheirValues() {
        jdbc.execute("ALTER TABLE agent_instances ADD COLUMN IF NOT EXISTS user_paused BOOLEAN");
        jdbc.execute(
                "ALTER TABLE agent_instances ADD COLUMN IF NOT EXISTS execution_wait VARCHAR(255)");
        jdbc.execute(
                "ALTER TABLE agent_instances ADD COLUMN IF NOT EXISTS wait_request_id VARCHAR(255)");
        var migration = new RetiredAgentControls(jdbc);
        migration.run(new DefaultApplicationArguments());
        migration.run(new DefaultApplicationArguments());
        var columns =
                jdbc.queryForList(
                        "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = 'AGENT_INSTANCES'",
                        Nullness.requireNonNull(String.class));
        assertFalse(columns.contains("USER_PAUSED"));
        assertFalse(columns.contains("EXECUTION_WAIT"));
        assertFalse(columns.contains("WAIT_REQUEST_ID"));
        assertTrue(columns.contains("ID"));
    }

    @Test
    void reconstructsExactGrantTypesAndRevocationWithoutRunnerState() {
        UUID session = UUID.randomUUID();
        String agent = UUID.randomUUID().toString();
        List<PermissionGrant> grants =
                List.of(
                        new PermissionGrant.ReadGrant(
                                "view_file", Path.of(".").toAbsolutePath(), List.of()),
                        new PermissionGrant.WriteGrant(
                                "write_file", Path.of(".").toAbsolutePath(), List.of()),
                        new PermissionGrant.CommandGrant("git", List.of("status"), List.of()),
                        new PermissionGrant.ExactToolGrant("custom", Map.of("mode", "read")));
        try {
            for (PermissionGrant grant : grants)
                history.append(
                        session, agent, "call", "RESOLVED", "LIKE_THIS", "CLIENT_RESPONSE", grant);
            history.append(
                    session, agent, "once", "RESOLVED", "ACCEPT_GENERIC", "CLIENT_RESPONSE", null);
            assertEquals(Set.copyOf(grants), history.grants(session, agent));
            assertTrue(history.grants(UUID.randomUUID(), agent).isEmpty());
            assertTrue(history.grants(session, "other").isEmpty());
            history.append(session, agent, "", "REVOKED", "", "CLIENT_RESPONSE", grants.get(0));
            assertFalse(history.grants(session, agent).contains(grants.get(0)));
            HitlRegistry restored = new HitlRegistry();
            restored.attachHistory(history);
            restored.setSession(agent, session);
            assertEquals(3, restored.grantLog(agent).size());
            assertTrue(restored.pendingFor(agent).isEmpty());
        } finally {
            repository.deleteAll(
                    repository.findBySessionIdAndAgentIdOrderByIdAsc(session.toString(), agent));
        }
    }

    @Test
    void failedDecisionWriteDoesNotReleasePendingCall() {
        @NonNull HitlHistory unavailable = mock();
        HitlRegistry registry = new HitlRegistry();
        registry.attachHistory(unavailable);
        UUID session = UUID.randomUUID();
        when(unavailable.grants(session, "agent")).thenReturn(Set.of());
        registry.setSession("agent", session);
        var future =
                registry.register(
                        "agent",
                        "call",
                        new ToolCall("test", Map.of(), "call"),
                        null,
                        List.of(VetoOption.ACCEPT_GENERIC),
                        null);
        doThrow(new IllegalStateException("storage unavailable"))
                .when(unavailable)
                .append(
                        eq(session),
                        eq("agent"),
                        eq("call"),
                        eq("RESOLVED"),
                        anyString(),
                        anyString(),
                        isNull());
        assertThrows(
                IllegalStateException.class,
                () -> registry.resolveOption("agent", "call", "ACCEPT_GENERIC"));
        assertFalse(future.isDone());
        assertTrue(registry.grantLog("agent").isEmpty());
        assertEquals(1, registry.pendingFor("agent").size());
    }
}
