package top.focess.veto.monitor;

import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Commits before model execution so a lost runtime cannot reset a request's allowance. */
@Service
public class RequestContinuationStore {
    private final @NonNull RequestContinuationRepository repository;

    public RequestContinuationStore(@NonNull RequestContinuationRepository repository) {
        this.repository = repository;
    }

    public record Checkpoint(@NonNull String task, long consumedCalls) {}

    @Transactional
    public void deleteSession(@NonNull String session) {
        repository.deleteByIdStartingWith(UUID.fromString(session) + ":");
    }

    private static @NonNull String key(
            @NonNull UUID session, @NonNull String agent, @NonNull String request) {
        return session + ":" + agent.length() + ":" + agent + ":" + request;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void save(
            @NonNull UUID session,
            @NonNull String agent,
            @NonNull String request,
            @NonNull String task,
            long calls) {
        repository.saveAndFlush(
                new RequestContinuationEntity(key(session, agent, request), task, calls));
    }

    @Transactional(readOnly = true)
    public @NonNull Optional<Checkpoint> load(
            @NonNull UUID session, @NonNull String agent, @NonNull String request) {
        return repository
                .findById(key(session, agent, request))
                .map(row -> new Checkpoint(row.getTask(), row.getConsumedCalls()));
    }
}
