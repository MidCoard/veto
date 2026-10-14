package top.focess.veto.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import top.focess.veto.agent.AgentService;
import top.focess.veto.agent.intercept.HitlRegistry;
import top.focess.veto.agent.intercept.VetoOption;
import top.focess.veto.api.agent.screening.Danger;
import top.focess.veto.api.llm.ToolCall;
import top.focess.veto.bus.SessionActions;
import top.focess.veto.session.SessionService;
import top.focess.veto.session.TestAgentSessions;
import top.focess.veto.vault.KeysteadVault;
import top.focess.veto.vault.TestUsers;

class HitlControllerTest {
    @Test
    void readOnlySnapshotsIncludeMateVetoesWithoutCrossingSessions() throws Exception {
        var registry = new HitlRegistry(null, Mockito.mock(SessionActions.class));
        var sessions = mock(SessionService.class);
        var service = mock(AgentService.class);
        var vault = mock(KeysteadVault.class);
        when(service.hasApprovalAgent(anyString(), eq("leader"))).thenReturn(true);
        when(service.hasApprovalAgent(anyString(), eq("mate"))).thenReturn(true);
        var mvc =
                MockMvcBuilders.standaloneSetup(new HitlController(sessions, registry, vault))
                        .build();
        UUID sessionId = UUID.randomUUID();
        var runtime = TestAgentSessions.bind(sessions, service);
        registry.setSession("leader", sessionId);
        registry.setSession("mate", sessionId);
        registry.setSession("outsider", UUID.randomUUID());
        var read = new ToolCall("view_file", Map.of("absolutePath", "fixture.txt"));
        var pending =
                registry.register(
                        "mate",
                        read.callId(),
                        read,
                        null,
                        List.of(VetoOption.ACCEPT_READ, VetoOption.READ_DECLINE),
                        Danger.SAFE);
        var foreign = registry.register("outsider", "foreign");
        when(vault.currentUser()).thenReturn(TestUsers.ALICE);
        when(sessions.primaryAgentIdFor("session", TestUsers.ALICE))
                .thenReturn(Optional.of("leader"));
        when(service.resolveVeto("mate", read.callId(), "ACCEPT_READ"))
                .thenAnswer(
                        ignored -> registry.resolveOption("mate", read.callId(), "ACCEPT_READ"));
        when(service.declineAllVetoes("mate")).thenAnswer(ignored -> registry.declineAll("mate"));

        mvc.perform(get("/api/sessions/session/vetoes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].agentId").value("mate"));
        assertFalse(pending.isDone());
        assertFalse(foreign.isDone());
        mvc.perform(get("/api/sessions/session/veto-history")).andExpect(status().isOk());
        mvc.perform(post("/api/sessions/session/cancel")).andExpect(status().isNotFound());
        when(vault.currentUser()).thenReturn(null);
        mvc.perform(get("/api/sessions/session/vetoes")).andExpect(status().isUnauthorized());
    }
}
