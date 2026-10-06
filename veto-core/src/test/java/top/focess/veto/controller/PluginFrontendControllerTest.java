package top.focess.veto.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;
import top.focess.veto.agent.SessionAgentRegistry;
import top.focess.veto.api.agent.tool.NativeTool;
import top.focess.veto.api.plugin.Scope;
import top.focess.veto.api.plugin.contract.FrontendContribution;
import top.focess.veto.api.plugin.contract.JsonValue;
import top.focess.veto.api.plugin.contract.StandardContributionPoints;
import top.focess.veto.api.plugin.contribution.Contribution;
import top.focess.veto.integration.plugins.PluginManager;
import top.focess.veto.integration.plugins.PluginRegistry;
import top.focess.veto.integration.plugins.SessionPlugins;
import top.focess.veto.integration.plugins.WorkflowPluginFixture;
import top.focess.veto.model.SessionEntity;
import top.focess.veto.model.SessionRepository;
import top.focess.veto.vault.ExecutionSecurity;
import top.focess.veto.vault.TestUsers;

class PluginFrontendControllerTest {
    @Test
    void modulesAndActionsRetainOnePublicationWhenManagerGenerationChanges() throws Exception {
        var handled = new AtomicInteger();
        var frontend =
                new FrontendContribution() {
                    @Override
                    public @NonNull String module() {
                        return "export default {}";
                    }

                    @Override
                    public @NonNull JsonValue handle(
                            Scope.@NonNull AgentScope scope,
                            @NonNull String action,
                            JsonValue.@NonNull ObjectValue arguments) {
                        handled.incrementAndGet();
                        return new JsonValue.StringValue("saved");
                    }
                };
        NativeTool<?> tool = mock(NativeTool.class);
        try (var fixture =
                new WorkflowPluginFixture(
                        List.of(
                                Contribution.of(
                                        StandardContributionPoints.FRONTEND, "panel", frontend),
                                Contribution.of(
                                        StandardContributionPoints.TOOLS, "display", tool)))) {
            var captured = fixture.manager.registry();
            var replacement = mock(PluginRegistry.class);
            doThrow(new AssertionError("Mixed manager publication"))
                    .when(replacement)
                    .entries(StandardContributionPoints.FRONTEND);
            SessionRepository sessions = mock(SessionRepository.class);
            SessionPlugins selected = mock(SessionPlugins.class);
            SessionAgentRegistry agents = mock(SessionAgentRegistry.class);
            var session = new SessionEntity(TestUsers.OWNER, "private", "D:/workspace");
            when(sessions.findFirstByNameAndUserIdOrderByLastActiveAtDesc(
                            "private", TestUsers.OWNER))
                    .thenReturn(Optional.of(session));
            when(selected.status(session.getId()))
                    .thenReturn(
                            List.of(
                                    new SessionPlugins.BoundPluginStatus(
                                            "fixture.workflow",
                                            "1.0.0",
                                            "1.0.0",
                                            true,
                                            SessionPlugins.BoundPluginAvailability.AVAILABLE)));
            when(agents.records(UUID.fromString(session.getId())))
                    .thenReturn(
                            List.of(
                                    new SessionAgentRegistry.AgentSummary(
                                            "local", "Local", null, null, null, null, false, null,
                                            null, null, null, true, null, null)));
            var controller =
                    new PluginFrontendController(
                            AuthorizationTestSupport.authorizer(user -> false),
                            sessions,
                            selected,
                            fixture.manager,
                            agents);
            clearInvocations(fixture.manager);
            SecurityContextHolder.setContext(ExecutionSecurity.contextFor(TestUsers.OWNER));
            try {
                when(fixture.manager.registry()).thenReturn(captured, replacement);
                var modules = controller.list("private").getBody();
                if (modules == null) throw new AssertionError("Missing frontend modules");
                assertEquals(1, modules.size());
                assertEquals(
                        Map.of("display", "plugin_fixture_workflow__display"),
                        modules.getFirst().tools());
                when(fixture.manager.registry()).thenReturn(captured, replacement);
                var result =
                        controller
                                .act(
                                        "private",
                                        new PluginFrontendController.ActionRequest(
                                                "fixture.workflow:panel",
                                                "local",
                                                "read",
                                                JsonNodeFactory.instance.objectNode()))
                                .getBody();
                if (result == null) throw new AssertionError("Missing frontend action result");
                assertEquals("saved", result.asText());
                assertEquals(1, handled.get());
                verify(fixture.manager, times(2)).registry();
                verifyNoInteractions(replacement);
            } finally {
                SecurityContextHolder.clearContext();
            }
        }
    }

    @Test
    void agentIdsOutsideTheAuthenticatedSessionAreIndistinguishableFromMissingIds() {
        SessionRepository sessions = mock(SessionRepository.class);
        SessionPlugins selected = mock(SessionPlugins.class);
        PluginManager plugins = mock(PluginManager.class);
        SessionAgentRegistry agents = mock(SessionAgentRegistry.class);
        var session = new SessionEntity(TestUsers.OWNER, "private", "D:/workspace");
        when(sessions.findFirstByNameAndUserIdOrderByLastActiveAtDesc("private", TestUsers.OWNER))
                .thenReturn(Optional.of(session));
        when(selected.status(session.getId())).thenReturn(List.of());
        when(agents.records(UUID.fromString(session.getId())))
                .thenReturn(
                        List.of(
                                new SessionAgentRegistry.AgentSummary(
                                        "local", "Local", null, null, null, null, false, null, null,
                                        null, null, true, null, null)));
        var controller =
                new PluginFrontendController(
                        AuthorizationTestSupport.authorizer(user -> false),
                        sessions,
                        selected,
                        plugins,
                        agents);
        SecurityContextHolder.setContext(ExecutionSecurity.contextFor(TestUsers.OWNER));
        try {
            for (String agent :
                    List.of("same-owner-other-session", "other-owner-agent", "missing")) {
                assertEquals(
                        HttpStatus.NOT_FOUND,
                        assertThrows(
                                        ResponseStatusException.class,
                                        () ->
                                                controller.act(
                                                        "private",
                                                        new PluginFrontendController.ActionRequest(
                                                                "one:panel",
                                                                agent,
                                                                "read",
                                                                JsonNodeFactory.instance
                                                                        .objectNode())))
                                .getStatusCode());
            }
            verifyNoInteractions(plugins);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void anonymousRequestsCannotReadModulesOrInvokeActions() {
        SecurityContextHolder.clearContext();
        SessionRepository sessions = mock(SessionRepository.class);
        SessionPlugins selected = mock(SessionPlugins.class);
        PluginManager plugins = mock(PluginManager.class);
        var controller =
                new PluginFrontendController(
                        AuthorizationTestSupport.authorizer(user -> false),
                        sessions,
                        selected,
                        plugins,
                        mock());
        assertEquals(
                HttpStatus.UNAUTHORIZED,
                assertThrows(ResponseStatusException.class, () -> controller.list("private"))
                        .getStatusCode());
        assertEquals(
                HttpStatus.UNAUTHORIZED,
                assertThrows(
                                ResponseStatusException.class,
                                () ->
                                        controller.act(
                                                "private",
                                                new PluginFrontendController.ActionRequest(
                                                        "third-party:panel",
                                                        "agent",
                                                        "read",
                                                        JsonNodeFactory.instance.objectNode())))
                        .getStatusCode());
        verifyNoInteractions(sessions, selected, plugins);
    }

    @Test
    void foreignSessionsCannotReadModulesOrInvokeActions() {
        SessionRepository sessions = mock(SessionRepository.class);
        SessionPlugins selected = mock(SessionPlugins.class);
        PluginManager plugins = mock(PluginManager.class);
        when(sessions.findFirstByNameAndUserIdOrderByLastActiveAtDesc(
                        "private", UUID.fromString("e4d90c3e-2176-523d-8dd9-e3deec8183b7")))
                .thenReturn(Optional.empty());
        var controller =
                new PluginFrontendController(
                        AuthorizationTestSupport.authorizer(user -> false),
                        sessions,
                        selected,
                        plugins,
                        mock());
        SecurityContextHolder.setContext(ExecutionSecurity.contextFor(TestUsers.BOB));
        try {
            assertEquals(
                    HttpStatus.NOT_FOUND,
                    assertThrows(ResponseStatusException.class, () -> controller.list("private"))
                            .getStatusCode());
            assertEquals(
                    HttpStatus.NOT_FOUND,
                    assertThrows(
                                    ResponseStatusException.class,
                                    () ->
                                            controller.act(
                                                    "private",
                                                    new PluginFrontendController.ActionRequest(
                                                            "third-party:panel",
                                                            "agent",
                                                            "read",
                                                            JsonNodeFactory.instance.objectNode())))
                            .getStatusCode());
            verifyNoInteractions(selected, plugins);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
