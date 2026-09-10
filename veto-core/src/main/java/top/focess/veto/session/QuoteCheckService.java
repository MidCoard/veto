package top.focess.veto.session;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import top.focess.veto.memory.TurnRecordRepository;

/** Returns citations bound to the successful request; never searches unrelated history. */
@Service
public class QuoteCheckService {
    private final @NonNull TurnRecordRepository repository;
    private final @NonNull ObjectMapper mapper;

    public QuoteCheckService(
            @NonNull TurnRecordRepository repository, @NonNull ObjectMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    public @NonNull List<@NonNull Check> check(
            @NonNull String session,
            @NonNull String agent,
            int turn,
            @NonNull String expectedBody) {
        if (expectedBody.length() > 64_000)
            throw new IllegalArgumentException("Answer exceeds quotation limit");
        var answer =
                repository
                        .findBySessionIdAndAgentIdAndTurnNumber(session, agent, turn)
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "Saved answer is not available"));
        try {
            var payload = mapper.readTree(answer.getPayload());
            if (!answer.getType().equals("ASSISTANT_RESPONSE")
                    || payload == null
                    || !payload.path("content").asText().equals(expectedBody))
                throw new IllegalArgumentException("Saved answer changed");
            var checks = payload.path("citation_context").path("checks");
            if (!checks.isArray()) return List.of();
            List<@NonNull Check> result =
                    mapper.convertValue(checks, new TypeReference<List<@NonNull Check>>() {});
            return result == null ? List.of() : result;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Saved citations are not available", e);
        }
    }

    public record Check(
            @NonNull String id,
            @NonNull String status,
            @NonNull List<@NonNull Match> matches,
            @NonNull List<@NonNull Reference> references) {}

    public record Reference(int messageIndex, @NonNull String status) {}

    public record Match(
            int turn,
            @NonNull String kind,
            @NonNull String field,
            @NonNull String url,
            @NonNull String method,
            @NonNull String excerpt,
            int highlightStart,
            int highlightEnd,
            int sourceStart,
            int sourceEnd) {}
}
