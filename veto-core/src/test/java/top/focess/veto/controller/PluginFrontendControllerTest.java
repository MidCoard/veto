package top.focess.veto.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import top.focess.veto.agent.SessionAgentRegistry;
import top.focess.veto.integration.plugins.PluginManager;
import top.focess.veto.integration.plugins.SessionPlugins;
import top.focess.veto.model.SessionEntity;
import top.focess.veto.model.SessionRepository;
import top.focess.veto.vault.UserContext;

class PluginFrontendControllerTest {
    @Test
    void agentIdsOutsideTheAuthenticatedSessionAreIndistinguishableFromMissingIds() {
        @NonNull SessionRepository sessions = mock();
        @NonNull SessionPlugins selected = mock();
        @NonNull PluginManager plugins = mock();
        @NonNull SessionAgentRegistry agents = mock();
        var session = new SessionEntity("owner", "private", "D:/workspace");
        when(sessions.findFirstByNameAndOwnerOrderByLastActiveAtDesc("private", "owner"))
                .thenReturn(Optional.of(session));
        when(selected.bindings(session.getId())).thenReturn(List.of());
        when(agents.records(UUID.fromString(session.getId())))
                .thenReturn(
                        List.of(
                                new SessionAgentRegistry.AgentSummary(
                                        "local", "Local", null, null, null, null, false, null, null,
                                        null, null, true, null, null)));
        var controller =
                new PluginFrontendController(
                        new RequestAuthorization(user -> false),
                        sessions,
                        selected,
                        plugins,
                        agents);
        UserContext.set("owner");
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
            UserContext.clear();
        }
    }

    @Test
    void anonymousRequestsCannotReadModulesOrInvokeActions() {
        UserContext.clear();
        @NonNull SessionRepository sessions = mock();
        @NonNull SessionPlugins selected = mock();
        @NonNull PluginManager plugins = mock();
        var controller =
                new PluginFrontendController(
                        new RequestAuthorization(user -> false),
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
        @NonNull SessionRepository sessions = mock();
        @NonNull SessionPlugins selected = mock();
        @NonNull PluginManager plugins = mock();
        when(sessions.findFirstByNameAndOwnerOrderByLastActiveAtDesc("private", "other"))
                .thenReturn(Optional.empty());
        var controller =
                new PluginFrontendController(
                        new RequestAuthorization(user -> false),
                        sessions,
                        selected,
                        plugins,
                        mock());
        UserContext.set("other");
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
            UserContext.clear();
        }
    }
}
