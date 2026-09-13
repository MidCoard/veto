package top.focess.veto.agent.intercept;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import top.focess.veto.util.Nullness;

@Service
public class HitlHistory {
    private final @NonNull HitlRecordRepository repository;
    private final @NonNull ObjectMapper mapper;

    public HitlHistory(@NonNull HitlRecordRepository repository, @NonNull ObjectMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    public record Decision(
            @NonNull String callId,
            @NonNull String event,
            @NonNull String decision,
            @NonNull String source) {}

    @Transactional(readOnly = true)
    public @NonNull List<Decision> decisions(@NonNull String agent) {
        return repository.findByAgentIdOrderByIdAsc(agent).stream()
                .map(
                        row ->
                                new Decision(
                                        row.getCallId(),
                                        row.getEvent(),
                                        row.getDecision(),
                                        row.getSource()))
                .toList();
    }

    /** Commit the decision and its grant together before releasing the waiting call. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void append(
            @NonNull UUID session,
            @NonNull String agent,
            @NonNull String call,
            @NonNull String event,
            @NonNull String decision,
            @NonNull String source,
            PermissionGrant grant) {
        try {
            String json =
                    grant == null
                            ? ""
                            : mapper.writerFor(Nullness.requireNonNull(PermissionGrant.class))
                                    .writeValueAsString(grant);
            repository.save(
                    new HitlRecordEntity(
                            session.toString(), agent, call, event, decision, source, json));
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Could not record approval", error);
        }
    }

    @Transactional(readOnly = true)
    public @NonNull Set<PermissionGrant> grants(@NonNull UUID session, @NonNull String agent) {
        Set<PermissionGrant> result = new LinkedHashSet<>();
        for (HitlRecordEntity record :
                repository.findBySessionIdAndAgentIdOrderByIdAsc(session.toString(), agent)) {
            if (record.getEvent().equals("CLEARED")) result.clear();
            if (record.getGrantJson().isEmpty()) continue;
            try {
                PermissionGrant grant =
                        Nullness.requireNonNull(
                                mapper.readValue(
                                        record.getGrantJson(),
                                        Nullness.requireNonNull(PermissionGrant.class)));
                if (record.getEvent().equals("RESOLVED")) result.add(grant);
                else if (record.getEvent().equals("REVOKED")) result.remove(grant);
            } catch (JsonProcessingException error) {
                throw new IllegalStateException("Cannot restore recorded authorization", error);
            }
        }
        return result;
    }
}
