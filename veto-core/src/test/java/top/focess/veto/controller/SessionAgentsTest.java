package top.focess.veto.controller;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import top.focess.veto.agent.SessionAgentRegistry;
import top.focess.veto.agent.identity.Role;
import top.focess.veto.agent.workspace.WorkspaceAdmissionPolicy;
import top.focess.veto.api.agent.AgentState;
import top.focess.veto.model.SessionEntity;
import top.focess.veto.session.SessionHistoryLoader;
import top.focess.veto.session.SessionService;
import top.focess.veto.vault.KeysteadVault;

class SessionAgentsTest {
    private final @NonNull SessionService sessions = mock();
    private final @NonNull KeysteadVault vault = mock();
    private final @NonNull SessionAgentRegistry registry = mock();
    private final @NonNull SessionHistoryLoader history = mock();
    private final @NonNull WorkspaceAdmissionPolicy workspaceAdmission = mock();
    private final @NonNull MockMvc mvc =
            MockMvcBuilders.standaloneSetup(
                            new SessionController(
                                    sessions,
                                    vault,
                                    history,
                                    mock(),
                                    registry,
                                    mock(),
                                    workspaceAdmission))
                    .build();

    @Test
    void sessionResponsesRenderLogicalRootsWithoutHostPaths() throws Exception {
        var hostRoot =
                Path.of(System.getProperty("user.dir"), "private-tenants", "owner", "project");
        var created = new SessionEntity("owner", "generated", hostRoot.toString());
        var legacy = new SessionEntity("owner", "legacy");
        var unavailable =
                new SessionEntity("owner", "unavailable", hostRoot.resolve("old").toString());
        when(vault.currentUser()).thenReturn("owner");
        when(workspaceAdmission.toClientPath("owner", hostRoot)).thenReturn("/0/project");
        when(workspaceAdmission.toClientPath("owner", hostRoot.resolve("old")))
                .thenThrow(new IllegalArgumentException("workspace mapping unavailable"));
        when(sessions.listSessions("owner")).thenReturn(List.of(created, legacy, unavailable));
        when(sessions.createSession(
                        eq("owner"),
                        eq("coder"),
                        isNull(),
                        eq("/0/project"),
                        eq(0),
                        any(),
                        eq(List.of())))
                .thenReturn(created);

        mvc.perform(get("/api/sessions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].workspaceRoots").value("/0/project"))
                .andExpect(jsonPath("$[0].id").value(created.getId()))
                .andExpect(jsonPath("$[1].workspaceRoots").isEmpty())
                .andExpect(jsonPath("$[2].workspaceRoots").isEmpty());
        mvc.perform(
                        post("/api/sessions")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"pattern\":\"coder\",\"workspaceRoots\":\"/0/project\",\"pluginIds\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workspaceRoots").value("/0/project"))
                .andExpect(jsonPath("$.name").value("generated"));
    }

    @Test
    void creationValidatesRequiredFieldsAndAllowsBackendNaming() throws Exception {
        when(vault.currentUser()).thenReturn("owner");
        when(workspaceAdmission.toClientPath("owner", Path.of("/workspace")))
                .thenReturn("/workspace");
        for (String body :
                List.of(
                        "{\"pattern\":\"coder\",\"workspaceRoots\":\"/workspace\"}",
                        "{\"pattern\":\"coder\",\"workspaceRoots\":\"/workspace\",\"pluginIds\":null}",
                        "{\"pattern\":\"coder\",\"workspaceRoots\":\"/workspace\",\"pluginIds\":[null]}",
                        "{\"workspaceRoots\":\"/workspace\",\"pluginIds\":[]}",
                        "{\"pattern\":\" \",\"workspaceRoots\":\"/workspace\",\"pluginIds\":[]}",
                        "{\"pattern\":\"coder\",\"pluginIds\":[]}",
                        "{\"pattern\":\"coder\",\"workspaceRoots\":\" \",\"pluginIds\":[]}",
                        "{\"pattern\":\"coder\",\"workspaceRoots\":\"/workspace\",\"currentWorkspaceRootIndex\":-1,\"pluginIds\":[]}")) {
            mvc.perform(post("/api/sessions").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(sessions);
        var created = new SessionEntity("owner", "generated", "/workspace");
        when(sessions.createSession(
                        eq("owner"),
                        eq("coder"),
                        isNull(),
                        eq("/workspace"),
                        eq(0),
                        any(),
                        eq(List.of())))
                .thenReturn(created);
        mvc.perform(
                        post("/api/sessions")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"pattern\":\"coder\",\"workspaceRoots\":\"/workspace\",\"pluginIds\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("generated"));
        verify(sessions)
                .createSession(
                        eq("owner"),
                        eq("coder"),
                        isNull(),
                        eq("/workspace"),
                        eq(0),
                        any(),
                        eq(List.of()));
    }

    @Test
    void conversationHistoryOnlyLoadsThePrimaryStream() throws Exception {
        SessionService.@NonNull SessionConfig cfg = mock();
        when(cfg.sessionId()).thenReturn("session-id");
        when(vault.currentUser()).thenReturn("owner");
        when(sessions.resolveByName("session", "owner")).thenReturn(Optional.of(cfg));
        when(sessions.primaryAgentIdFor("session", "owner")).thenReturn(Optional.of("primary"));
        when(history.load("session-id", "primary")).thenReturn(List.of());
        mvc.perform(get("/api/sessions/session/history")).andExpect(status().isOk());
        verify(history).load("session-id", "primary");
        verify(history, never()).load(anyString());
    }

    @Test
    void requiresOwnerBeforeReadingRuntimeMetadata() throws Exception {
        mvc.perform(get("/api/sessions/private/execution")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/sessions/private/agents")).andExpect(status().isUnauthorized());
        when(vault.currentUser()).thenReturn("other-user");
        when(sessions.resolveByName("private", "other-user")).thenReturn(Optional.empty());
        mvc.perform(get("/api/sessions/private/agents")).andExpect(status().isNotFound());
        mvc.perform(get("/api/sessions/private/execution")).andExpect(status().isNotFound());
        verifyNoInteractions(registry);
    }

    @Test
    void executionSnapshotReportsQueuedWorkWithoutReturningHistory() throws Exception {
        UUID sessionId = UUID.randomUUID();
        SessionService.@NonNull SessionConfig cfg = mock();
        top.focess.veto.agent.@NonNull VetoAgent agent = mock();
        when(cfg.sessionId()).thenReturn(sessionId.toString());
        when(vault.currentUser()).thenReturn("owner");
        when(sessions.resolveByName("session", "owner")).thenReturn(Optional.of(cfg));
        when(agent.id()).thenReturn("primary");
        when(agent.hasPendingWork()).thenReturn(true);
        when(registry.agents(sessionId))
                .thenReturn(List.of(new SessionAgentRegistry.Entry(sessionId, null, null, agent)));
        mvc.perform(get("/api/sessions/session/execution"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].agentId").value("primary"))
                .andExpect(jsonPath("$[0].busy").value(true));
        verifyNoInteractions(history);
    }

    @Test
    void includesReaderParentageAndStateWithoutExportingHistory() throws Exception {
        UUID sessionId = UUID.randomUUID();
        SessionService.@NonNull SessionConfig cfg = mock();
        when(cfg.sessionId()).thenReturn(sessionId.toString());
        when(vault.currentUser()).thenReturn("owner");
        when(sessions.resolveByName("session", "owner")).thenReturn(Optional.of(cfg));
        when(registry.records(sessionId))
                .thenReturn(
                        List.of(
                                new SessionAgentRegistry.AgentSummary(
                                        "reader",
                                        "Web reader",
                                        Role.STANDALONE,
                                        AgentState.TERMINATED,
                                        "mate",
                                        "read-call",
                                        false,
                                        null,
                                        null,
                                        null,
                                        null,
                                        false,
                                        null,
                                        null)));
        mvc.perform(get("/api/sessions/session/agents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].parentAgentId").value("mate"))
                .andExpect(jsonPath("$[0].parentCallId").value("read-call"))
                .andExpect(jsonPath("$[0].role").value("STANDALONE"))
                .andExpect(jsonPath("$[0].state").value("TERMINATED"))
                .andExpect(jsonPath("$[0].userInteractionEnabled").value(false))
                .andExpect(jsonPath("$[0].history").doesNotExist());
        when(registry.records(sessionId)).thenReturn(List.of());
        mvc.perform(get("/api/sessions/session/agents")).andExpect(jsonPath("$.length()").value(0));
    }
}
