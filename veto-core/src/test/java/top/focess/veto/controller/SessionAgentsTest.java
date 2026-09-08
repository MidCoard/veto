package top.focess.veto.controller;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import top.focess.veto.agent.AgentState;
import top.focess.veto.agent.SessionAgentRegistry;
import top.focess.veto.agent.VetoAgent;
import top.focess.veto.agent.identity.AgentPersona;
import top.focess.veto.session.SessionService;
import top.focess.veto.vault.KeysteadVault;

class SessionAgentsTest {
    private final @NonNull SessionService sessions = mock();
    private final @NonNull KeysteadVault vault = mock();
    private final @NonNull SessionAgentRegistry registry = mock();
    private final @NonNull MockMvc mvc =
            MockMvcBuilders.standaloneSetup(
                            new SessionController(sessions, vault, mock(), mock(), registry))
                    .build();

    @Test
    void requiresOwnerBeforeReadingRuntimeMetadata() throws Exception {
        mvc.perform(get("/api/sessions/private/agents")).andExpect(status().isUnauthorized());
        when(vault.currentUser()).thenReturn("other-user");
        when(sessions.resolveByName("private", "other-user")).thenReturn(Optional.empty());
        mvc.perform(get("/api/sessions/private/agents")).andExpect(status().isNotFound());
        verifyNoInteractions(registry);
    }

    @Test
    void includesReaderParentageAndStateWithoutExportingHistory() throws Exception {
        UUID sessionId = UUID.randomUUID();
        SessionService.@NonNull SessionConfig cfg = mock();
        when(cfg.sessionId()).thenReturn(sessionId.toString());
        when(vault.currentUser()).thenReturn("owner");
        when(sessions.resolveByName("session", "owner")).thenReturn(Optional.of(cfg));
        @NonNull VetoAgent child = mock();
        when(child.id()).thenReturn("reader");
        when(child.name()).thenReturn("Web reader");
        when(child.state()).thenReturn(AgentState.RUNNING);
        when(child.persona())
                .thenReturn(new AgentPersona("reader", "Web reader", "", Set.of(), List.of()));
        when(registry.agents(sessionId))
                .thenReturn(
                        List.of(
                                new SessionAgentRegistry.Entry(
                                        sessionId, "mate", "read-call", child)));
        mvc.perform(get("/api/sessions/session/agents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].parentAgentId").value("mate"))
                .andExpect(jsonPath("$[0].parentCallId").value("read-call"))
                .andExpect(jsonPath("$[0].role").value("STANDALONE"))
                .andExpect(jsonPath("$[0].state").value("RUNNING"))
                .andExpect(jsonPath("$[0].history").doesNotExist());
        verify(child, never()).history();
        when(registry.agents(sessionId)).thenReturn(List.of());
        mvc.perform(get("/api/sessions/session/agents")).andExpect(jsonPath("$.length()").value(0));
    }
}
