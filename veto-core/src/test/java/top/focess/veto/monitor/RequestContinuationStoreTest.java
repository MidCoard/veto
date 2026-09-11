package top.focess.veto.monitor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;

class RequestContinuationStoreTest {
    @Test
    void checkpointScopeAndSessionDeletionKeepOtherRecipientsSeparate() {
        @NonNull RequestContinuationRepository repository = mock();
        Map<String, RequestContinuationEntity> rows = new HashMap<>();
        when(repository.saveAndFlush(any()))
                .thenAnswer(
                        invocation -> {
                            RequestContinuationEntity row = invocation.getArgument(0);
                            if (row == null) throw new AssertionError("Missing checkpoint");
                            rows.put(row.getId(), row);
                            return row;
                        });
        when(repository.findById(anyString()))
                .thenAnswer(invocation -> Optional.ofNullable(rows.get(invocation.getArgument(0))));
        doAnswer(
                        invocation -> {
                            String prefix = invocation.getArgument(0);
                            if (prefix == null) throw new AssertionError("Missing scope");
                            rows.keySet().removeIf(key -> key.startsWith(prefix));
                            return null;
                        })
                .when(repository)
                .deleteByIdStartingWith(anyString());
        var store = new RequestContinuationStore(repository);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        store.save(first, "agent", "request", "first", 2);
        store.save(second, "agent", "request", "second", 3);
        store.save(first, "other", "request", "third", 4);
        assertEquals("first", store.load(first, "agent", "request").orElseThrow().task());
        assertEquals(3, store.load(second, "agent", "request").orElseThrow().consumedCalls());
        assertEquals(4, store.load(first, "other", "request").orElseThrow().consumedCalls());
        assertTrue(store.load(first, "agent", "unknown").isEmpty());
        store.deleteSession(first.toString());
        assertTrue(store.load(first, "agent", "request").isEmpty());
        assertTrue(store.load(first, "other", "request").isEmpty());
        assertEquals("second", store.load(second, "agent", "request").orElseThrow().task());
    }
}
