package top.focess.veto.monitor;

import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import top.focess.veto.agent.SessionAgentRegistry;
import top.focess.veto.group.Group;
import top.focess.veto.group.GroupRegistry;
import top.focess.veto.session.SessionService;
import top.focess.veto.vault.KeysteadVault;
import top.focess.veto.vault.UserContext;

@Service
public class MonitorAgentActivator {
    private final @NonNull SessionService sessions;
    private final @NonNull SessionAgentRegistry agents;
    private final @NonNull KeysteadVault vault;
    private final @NonNull GroupRegistry groups;

    public MonitorAgentActivator(
            @NonNull SessionService sessions,
            @NonNull SessionAgentRegistry agents,
            @NonNull KeysteadVault vault,
            @NonNull GroupRegistry groups) {
        this.sessions = sessions;
        this.agents = agents;
        this.vault = vault;
        this.groups = groups;
    }

    public void wake(@NonNull MonitorRecord record) {
        if (!vault.isUnlocked(record.owner())) return;
        UUID session = UUID.fromString(record.sessionId());
        String previous = UserContext.get();
        UserContext.set(record.owner());
        try {
            if (signal(session, record.agentId())) return;
            if (sessions.activateForMonitor(session, record.owner(), record.agentId())) {
                signal(session, record.agentId());
            }
        } finally {
            if (previous == null) UserContext.clear();
            else UserContext.set(previous);
        }
    }

    private boolean signal(@NonNull UUID session, @NonNull String agentId) {
        if (groups.snapshot().values().stream()
                .anyMatch(
                        group ->
                                session.equals(group.sessionId())
                                        && group.state() == Group.GroupState.RECOVERING))
            return false;
        for (SessionAgentRegistry.Entry entry : agents.agents(session)) {
            if (entry.agent().id().equals(agentId)) {
                entry.agent().signalMonitor();
                return true;
            }
        }
        return false;
    }
}
