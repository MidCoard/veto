package top.focess.veto.agent.continuation;

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

    public record Checkpoint(@NonNull String task, long consumedCalls, Long grantedCalls) {
        public Checkpoint(@NonNull String task, long consumedCalls) {
            this(task, consumedCalls, null);
        }
    }

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
        save(session, agent, request, task, calls, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void save(
            @NonNull UUID session,
            @NonNull String agent,
            @NonNull String request,
            @NonNull String task,
            long calls,
            Long grantedCalls) {
        if (calls < 0 || (grantedCalls != null && grantedCalls < -1))
            throw new IllegalArgumentException("Invalid request checkpoint");
        String id = key(session, agent, request);
        var previous = repository.findById(id).orElse(null);
        if (previous != null) {
            calls = Math.max(calls, previous.getConsumedCalls());
            task = previous.getTask();
            Long priorGrant = previous.getGrantedCalls();
            if (priorGrant != null) {
                grantedCalls =
                        grantedCalls == null
                                ? priorGrant
                                : priorGrant == -1 || grantedCalls == -1
                                        ? -1L
                                        : Math.max(priorGrant, grantedCalls);
            }
        }
        repository.saveAndFlush(new RequestContinuationEntity(id, task, calls, grantedCalls));
    }

    @Transactional
    public @NonNull Optional<Checkpoint> load(
            @NonNull UUID session, @NonNull String agent, @NonNull String request) {
        return repository
                .findById(key(session, agent, request))
                .map(
                        row ->
                                new Checkpoint(
                                        row.getTask(),
                                        row.getConsumedCalls(),
                                        row.getGrantedCalls()));
    }
}
