package top.focess.veto.memory;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import top.focess.veto.agent.TurnRecord;

/**
 * Verifies {@link MemoryCaptureService} captures a turn into BOTH sinks: the semantic Session LTM
 * ({@link MemoryStore}) and the durable raw-turn log ({@link TurnRecordRepository}). Previously
 * capture wrote only the semantic entry and was never called by the loop at all.
 */
class MemoryCaptureServiceTest {

    @Test
    void captureWritesSemanticLtmAndRawTurnLog() {
        InMemoryMemoryStore store = new InMemoryMemoryStore();
        TurnRecordRepository repo = mock(TurnRecordRepository.class);
        MemoryCaptureService service = new MemoryCaptureService(store, repo, new ObjectMapper());

        UUID session = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        service.capture(TurnRecord.userPrompt(1, "hello world"), session, user);

        // (a) semantic Session LTM.
        assertEquals(1, store.size(), "the turn is captured into Session LTM");
        // (b) raw-turn log.
        verify(repo, times(1)).save(any(TurnRecordEntity.class));
    }

    @Test
    void rawTurnLogCarriesTenantAndPayload() {
        InMemoryMemoryStore store = new InMemoryMemoryStore();
        TurnRecordRepository repo = mock(TurnRecordRepository.class);
        MemoryCaptureService service = new MemoryCaptureService(store, repo, new ObjectMapper());

        UUID session = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        service.capture(TurnRecord.userPrompt(7, "do the thing"), session, user);

        org.mockito.ArgumentCaptor<TurnRecordEntity> captor =
                org.mockito.ArgumentCaptor.forClass(TurnRecordEntity.class);
        verify(repo).save(captor.capture());
        TurnRecordEntity saved = captor.getValue();
        assertEquals(user.toString(), saved.getUserId());
        assertEquals(session.toString(), saved.getSessionId());
        assertEquals(7, saved.getTurnNumber());
        assertEquals("USER_PROMPT", saved.getType());
        assertTrue(saved.getPayload().contains("do the thing"));
    }
}
