package top.focess.veto.agent;

import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import top.focess.veto.model.AgentInstanceRepository;
import top.focess.veto.util.Nullness;

@Service
public class AgentWaitStore {
    public enum Reason {
        APPROVAL,
        QUESTION,
        BREAKER
    }

    public record Wait(@NonNull Reason reason, String requestId) {}

    private final @NonNull AgentInstanceRepository repository;

    public AgentWaitStore(@NonNull AgentInstanceRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public @NonNull Optional<Wait> load(@NonNull UUID session, @NonNull String agent) {
        return repository
                .findById(agent)
                .filter(row -> row.getSessionId().equals(session.toString()))
                .flatMap(
                        row -> {
                            String reason = row.getExecutionWait();
                            return reason == null
                                    ? Optional.empty()
                                    : Optional.of(
                                            new Wait(
                                                    Nullness.requireNonNull(Reason.valueOf(reason)),
                                                    row.getWaitRequestId()));
                        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void save(@NonNull UUID session, @NonNull String agent, Wait wait) {
        if (repository.updateExecutionWait(
                        session.toString(),
                        agent,
                        wait == null ? null : wait.reason().name(),
                        wait == null ? null : wait.requestId())
                != 1) {
            throw new IllegalStateException(
                    "Agent identity is unavailable for execution wait control");
        }
    }
}
