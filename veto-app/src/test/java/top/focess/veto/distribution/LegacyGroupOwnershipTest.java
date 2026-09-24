package top.focess.veto.distribution;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.checkerframework.framework.qual.DefaultQualifier;
import org.checkerframework.framework.qual.TypeUseLocation;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.veto.builtin.group.GroupHistoryView;
import top.focess.veto.integration.plugins.SessionPlugins;
import top.focess.veto.model.AgentEntity;
import top.focess.veto.model.AgentInstanceRepository;
import top.focess.veto.model.SessionEntity;
import top.focess.veto.model.SessionRepository;

@DefaultQualifier(
        value = NonNull.class,
        locations = {
            TypeUseLocation.FIELD,
            TypeUseLocation.PARAMETER,
            TypeUseLocation.RETURN,
            TypeUseLocation.UPPER_BOUND
        })
class LegacyGroupOwnershipTest {
    @Test
    void validatesEntireRosterBeforeClaimingAndRejectsForeignChildren() {
        @NonNull AgentInstanceRepository agents = mock();
        @NonNull SessionRepository sessions = mock();
        @NonNull SessionPlugins selection = mock();
        var session = new SessionEntity("owner", "test");
        session.setPrimaryAgentId("leader");
        when(sessions.findById(session.getId())).thenReturn(Optional.of(session));
        when(selection.includes(session.getId(), "builtin")).thenReturn(true);
        @NonNull AgentEntity leader = mock();
        @NonNull AgentEntity first = mock();
        @NonNull AgentEntity foreign = mock();
        when(leader.getSessionId()).thenReturn(session.getId());
        when(leader.getRole()).thenReturn(AgentEntity.Role.PRIMARY);
        when(first.getSessionId()).thenReturn(session.getId());
        when(first.getRole()).thenReturn(AgentEntity.Role.SUB);
        when(first.getRuntimeRole()).thenReturn("MATE");
        when(foreign.getSessionId()).thenReturn("other-session");
        when(agents.findById("leader")).thenReturn(Optional.of(leader));
        when(agents.findById("first")).thenReturn(Optional.of(first));
        when(agents.findById("foreign")).thenReturn(Optional.of(foreign));
        Map<String, String> roster = new LinkedHashMap<>();
        roster.put("first", "first");
        roster.put("foreign", "foreign");
        var ownership = new LegacyGroupOwnership(agents, sessions, selection);
        var view =
                new GroupHistoryView(
                        "group",
                        "leader",
                        "brief",
                        "ACTIVE",
                        Instant.EPOCH,
                        List.of(),
                        false,
                        false,
                        List.of(),
                        roster);
        assertThrows(
                SecurityException.class, () -> ownership.claim(session.getId(), view, "builtin"));
        verify(first, never()).claimPlugin(anyString(), anyString());
        verify(agents, never()).saveAll(any());
        var valid =
                new GroupHistoryView(
                        "group",
                        "leader",
                        "brief",
                        "COMPLETED",
                        Instant.EPOCH,
                        List.of(),
                        false,
                        false,
                        List.of(),
                        Map.of("first", "member"));
        ownership.claim(session.getId(), valid, "builtin");
        verify(first).claimPlugin("builtin", "leader");
        verify(agents).saveAll(List.of(first));
    }

    @Test
    void rejectsLeaderThatIsNotTheSessionsPrimaryIdentity() {
        @NonNull AgentInstanceRepository agents = mock();
        @NonNull SessionRepository sessions = mock();
        @NonNull SessionPlugins selection = mock();
        var session = new SessionEntity("owner", "test");
        session.setPrimaryAgentId("primary");
        when(sessions.findById(session.getId())).thenReturn(Optional.of(session));
        when(selection.includes(session.getId(), "builtin")).thenReturn(true);
        @NonNull AgentEntity leader = mock();
        when(leader.getSessionId()).thenReturn(session.getId());
        when(leader.getRole()).thenReturn(AgentEntity.Role.PRIMARY);
        when(agents.findById("leader")).thenReturn(Optional.of(leader));
        var ownership = new LegacyGroupOwnership(agents, sessions, selection);
        var view =
                new GroupHistoryView(
                        "group",
                        "leader",
                        "brief",
                        "COMPLETED",
                        Instant.EPOCH,
                        List.of(),
                        false,
                        false,
                        List.of(),
                        Map.of());
        assertThrows(
                SecurityException.class, () -> ownership.claim(session.getId(), view, "builtin"));
        session.setPrimaryAgentId("leader");
        when(leader.getRole()).thenReturn(AgentEntity.Role.SUB);
        assertThrows(
                SecurityException.class, () -> ownership.claim(session.getId(), view, "builtin"));
        verify(agents, never()).saveAll(any());
    }
}
