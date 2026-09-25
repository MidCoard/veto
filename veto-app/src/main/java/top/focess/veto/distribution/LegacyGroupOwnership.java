package top.focess.veto.distribution;

import java.util.ArrayList;
import java.util.List;
import org.checkerframework.framework.qual.DefaultQualifier;
import org.checkerframework.framework.qual.TypeUseLocation;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import top.focess.veto.builtin.group.GroupHistoryView;
import top.focess.veto.integration.plugins.SessionPlugins;
import top.focess.veto.model.AgentEntity;
import top.focess.veto.model.AgentInstanceRepository;
import top.focess.veto.model.SessionRepository;

/** Narrow upgrade authority based only on the host's durable legacy roster. */
@Component
@DefaultQualifier(
        value = NonNull.class,
        locations = {
            TypeUseLocation.FIELD,
            TypeUseLocation.PARAMETER,
            TypeUseLocation.RETURN,
            TypeUseLocation.UPPER_BOUND
        })
public class LegacyGroupOwnership {
    private final AgentInstanceRepository agents;
    private final SessionRepository sessions;
    private final SessionPlugins selection;

    /** Creates the authority from its agent, session, and plugin-selection repositories. */
    public LegacyGroupOwnership(
            AgentInstanceRepository agents, SessionRepository sessions, SessionPlugins selection) {
        this.agents = agents;
        this.sessions = sessions;
        this.selection = selection;
    }

    /** Claims unowned legacy mates for the namespace, rejecting any identity mismatch. */
    @Transactional
    public void claim(String sessionId, GroupHistoryView view, String namespace) {
        var mates = view.mates();
        if (view.historical()
                || !List.of("ACTIVE", "RECOVERING", "COMPLETED").contains(view.state())
                || mates == null) return;
        var session = sessions.findById(sessionId);
        if (session.isEmpty() || !selection.includes(sessionId, namespace)) return;
        var leader =
                agents.findById(view.leaderId())
                        .orElseThrow(() -> new IllegalStateException("Legacy leader missing"));
        if (!leader.getSessionId().equals(sessionId)
                || leader.getRole() != AgentEntity.Role.PRIMARY
                || !view.leaderId().equals(session.get().getPrimaryAgentId()))
            throw new SecurityException("Legacy leader identity mismatch");
        List<AgentEntity> pending = new ArrayList<>();
        for (String mateId : mates.keySet()) {
            var mate =
                    agents.findById(mateId)
                            .orElseThrow(() -> new IllegalStateException("Legacy member missing"));
            if (mateId.equals(view.leaderId())
                    || !mate.getSessionId().equals(sessionId)
                    || mate.getRole() != AgentEntity.Role.SUB
                    || !"MATE".equals(mate.getRuntimeRole()))
                throw new SecurityException("Legacy member identity mismatch");
            if (namespace.equals(mate.getPluginNamespace())
                    && view.leaderId().equals(mate.getParentAgentId())) continue;
            if (mate.getPluginNamespace() != null || mate.getParentAgentId() != null)
                throw new SecurityException("Legacy member already owned");
            pending.add(mate);
        }
        for (var mate : pending) mate.claimPlugin(namespace, view.leaderId());
        agents.saveAll(pending);
    }
}
