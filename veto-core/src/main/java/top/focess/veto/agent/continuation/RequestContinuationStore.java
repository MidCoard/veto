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

    /** Creates the store over its repository. */
    public RequestContinuationStore(@NonNull RequestContinuationRepository repository) {
        this.repository = repository;
    }

    /** The committed task, consumed-call count, and optional granted-call allowance. */
    public record Checkpoint(@NonNull String task, long consumedCalls, Long grantedCalls) {
        /** Checkpoint with no explicit granted-call allowance. */
        public Checkpoint(@NonNull String task, long consumedCalls) {
            this(task, consumedCalls, null);
        }
    }

    /** Deletes every continuation row belonging to the session. */
    @Transactional
    public void deleteSession(@NonNull String session) {
        repository.deleteByIdStartingWith(UUID.fromString(session) + ":");
    }

    private static @NonNull String key(
            @NonNull UUID session, @NonNull String agent, @NonNull String request) {
        return session + ":" + agent.length() + ":" + agent + ":" + request;
    }

    /** Commits a checkpoint with no explicit allowance in its own transaction. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void save(
            @NonNull UUID session,
            @NonNull String agent,
            @NonNull String request,
            @NonNull String task,
            long calls) {
        save(session, agent, request, task, calls, null);
    }

    /** Commits a checkpoint in its own transaction; consumed calls and grants never regress. */
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

    /** Loads the committed checkpoint for one session/agent/request, if any. */
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
