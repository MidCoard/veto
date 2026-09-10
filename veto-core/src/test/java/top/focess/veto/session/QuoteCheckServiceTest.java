package top.focess.veto.session;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.veto.agent.TurnRecord;
import top.focess.veto.agent.TurnType;
import top.focess.veto.memory.TurnRecordEntity;
import top.focess.veto.memory.TurnRecordRepository;

class QuoteCheckServiceTest {
    @Test
    void readsOnlyBoundCitationsOfTheSavedAnswer() {
        var mapper = new ObjectMapper();
        @NonNull TurnRecordRepository repo = mock();
        var check =
                new QuoteCheckService.Check(
                        "meeting",
                        "not_found",
                        List.of(),
                        List.of(new QuoteCheckService.Reference(7, "not_found")));
        String body = "[Meeting](cite:meeting)";
        var row =
                TurnRecordEntity.of(
                        new TurnRecord(
                                3,
                                TurnType.ASSISTANT_RESPONSE,
                                Map.of(
                                        "content",
                                        body,
                                        "citation_context",
                                        Map.of("checks", List.of(check))),
                                null),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "author",
                        mapper);
        when(repo.findBySessionIdAndAgentIdAndTurnNumber("session", "author", 3))
                .thenReturn(Optional.of(row));
        var service = new QuoteCheckService(repo, mapper);
        assertEquals(List.of(check), service.check("session", "author", 3, body));
        assertThrows(
                IllegalArgumentException.class,
                () -> service.check("session", "author", 3, "changed"));
        verify(repo, times(2)).findBySessionIdAndAgentIdAndTurnNumber("session", "author", 3);
        verifyNoMoreInteractions(repo);
    }

    @Test
    void ordinaryBlockquoteDoesNotAcquireSourcesFromHistory() {
        var mapper = new ObjectMapper();
        @NonNull TurnRecordRepository repo = mock();
        var row =
                TurnRecordEntity.of(
                        TurnRecord.assistantResponse(3, "> Existing wording"),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "author",
                        mapper);
        when(repo.findBySessionIdAndAgentIdAndTurnNumber("session", "author", 3))
                .thenReturn(Optional.of(row));
        assertTrue(
                new QuoteCheckService(repo, mapper)
                        .check("session", "author", 3, "> Existing wording")
                        .isEmpty());
        verify(repo).findBySessionIdAndAgentIdAndTurnNumber("session", "author", 3);
        verifyNoMoreInteractions(repo);
    }
}
