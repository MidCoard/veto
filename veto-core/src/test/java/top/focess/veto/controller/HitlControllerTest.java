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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import top.focess.veto.agent.AgentService;
import top.focess.veto.agent.intercept.HitlRegistry;
import top.focess.veto.agent.intercept.VetoOption;
import top.focess.veto.agent.screening.Danger;
import top.focess.veto.agent.tool.ToolDocs;
import top.focess.veto.llm.core.ToolCall;
import top.focess.veto.session.SessionService;
import top.focess.veto.vault.KeysteadVault;

class HitlControllerTest {
    @Test
    void parentSessionCanListResolveAndCancelMateVetoesWithoutCrossingSessions() throws Exception {
        var registry = new HitlRegistry();
        var sessions = mock(ToolDocs.nonNullClass(SessionService.class));
        var service = mock(ToolDocs.nonNullClass(AgentService.class));
        var vault = mock(ToolDocs.nonNullClass(KeysteadVault.class));
        var mvc =
                MockMvcBuilders.standaloneSetup(
                                new HitlController(sessions, service, registry, vault))
                        .build();
        UUID sessionId = UUID.randomUUID();
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
        when(vault.currentUser()).thenReturn("alice");
        when(sessions.primaryAgentIdFor("session", "alice")).thenReturn(Optional.of("leader"));
        when(service.resolveVeto("mate", read.callId(), "ACCEPT_READ"))
                .thenAnswer(
                        ignored -> registry.resolveOption("mate", read.callId(), "ACCEPT_READ"));
        when(service.declineAllVetoes("mate")).thenAnswer(ignored -> registry.declineAll("mate"));

        mvc.perform(get("/api/sessions/session/vetoes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].agentId").value("mate"));
        mvc.perform(
                        post("/api/sessions/session/vetoes/foreign")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"option\":\"ACCEPT_READ\"}"))
                .andExpect(status().isNotFound());
        assertFalse(foreign.isDone());
        var collision =
                registry.register(
                        "leader",
                        read.callId(),
                        read,
                        null,
                        List.of(VetoOption.ACCEPT_READ, VetoOption.READ_DECLINE),
                        Danger.SAFE);
        mvc.perform(
                        post("/api/sessions/session/vetoes/" + read.callId())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"option\":\"ACCEPT_READ\"}"))
                .andExpect(status().isConflict());
        assertFalse(collision.isDone());
        assertFalse(pending.isDone());
        registry.declineOption("leader", read.callId());
        mvc.perform(
                        post("/api/sessions/session/vetoes/" + read.callId())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"option\":\"ACCEPT_READ\"}"))
                .andExpect(status().isNoContent());
        assertTrue(pending.isDone());
        var cancelled = registry.register("mate", "cancel-me");
        mvc.perform(post("/api/sessions/session/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.declined").value(1));
        assertTrue(cancelled.isDone());
        assertFalse(foreign.isDone());
        var terminated = registry.register("mate", "terminate-me");
        registry.clear("mate");
        assertTrue(terminated.isDone(), "Terminating a parked Mate must release its wait");
        assertEquals(List.of("leader"), registry.sessionAgents("leader"));
        when(vault.currentUser()).thenReturn(null);
        mvc.perform(get("/api/sessions/session/vetoes")).andExpect(status().isUnauthorized());
    }
}
