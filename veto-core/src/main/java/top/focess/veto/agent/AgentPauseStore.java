package top.focess.veto.agent;

import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import top.focess.veto.model.AgentInstanceRepository;

@Service
public class AgentPauseStore {
    private final @NonNull AgentInstanceRepository repository;

    public AgentPauseStore(@NonNull AgentInstanceRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public boolean load(@NonNull UUID session, @NonNull String agent) {
        return repository
                .findById(agent)
                .filter(row -> row.getSessionId().equals(session.toString()))
                .map(row -> row.isUserPaused())
                .orElse(false);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void save(@NonNull UUID session, @NonNull String agent, boolean paused) {
        if (repository.updateUserPaused(session.toString(), agent, paused) != 1) {
            throw new IllegalStateException("Agent identity is unavailable for pause control");
        }
    }
}
