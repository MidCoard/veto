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

    /** Creates the service over the durable turn-record log. */
    public QuoteCheckService(
            @NonNull TurnRecordRepository repository, @NonNull ObjectMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    /**
     * Returns the citation checks saved with one agent turn's assistant response, after verifying
     * the stored response still matches {@code expectedBody}.
     *
     * @throws AnswerNotFoundException if the requested turn is missing or is not an answer
     * @throws AnswerChangedException if the stored answer no longer matches the requested body
     * @throws IllegalArgumentException if the quotation limit is exceeded or saved data is
     *     unreadable
     */
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
                        .orElseThrow(AnswerNotFoundException::new);
        if (!answer.getType().equals("ASSISTANT_RESPONSE")) throw new AnswerNotFoundException();
        try {
            var payload = mapper.readTree(answer.getPayload());
            if (payload == null) throw new IllegalArgumentException("Saved answer is unreadable");
            if (!payload.path("content").asText().equals(expectedBody))
                throw new AnswerChangedException();
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

    /** The requested turn has no saved assistant answer to check. */
    public static final class AnswerNotFoundException extends IllegalArgumentException {
        public AnswerNotFoundException() {
            super("Saved answer is not available");
        }
    }

    /** The caller's answer version no longer matches the saved response. */
    public static final class AnswerChangedException extends IllegalArgumentException {
        public AnswerChangedException() {
            super("Saved answer changed");
        }
    }

    /** One citation check with its verification status, located matches, and message references. */
    public record Check(
            @NonNull String id,
            @NonNull String status,
            @NonNull List<@NonNull Match> matches,
            @NonNull List<@NonNull Reference> references) {}

    /** A referenced message index and its verification status. */
    public record Reference(int messageIndex, @NonNull String status) {}

    /** A located quote match: source turn, kind, field, URL, and excerpt offsets. */
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
