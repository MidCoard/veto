package top.focess.veto.agent.continuation;

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
        assertNull(store.load(first, "agent", "request").orElseThrow().grantedCalls());
        store.save(first, "agent", "request", "replacement must not rewrite task", 4, 6L);
        store.save(first, "agent", "request", "stale checkpoint", 1, 2L);
        var restarted = new RequestContinuationStore(repository);
        var restored = restarted.load(first, "agent", "request").orElseThrow();
        assertEquals("first", restored.task());
        assertEquals(4, restored.consumedCalls());
        assertEquals(Long.valueOf(6), restored.grantedCalls());
        store.save(first, "agent", "request", "legacy writer", 3);
        assertEquals(
                Long.valueOf(6),
                store.load(first, "agent", "request").orElseThrow().grantedCalls());
        store.save(first, "agent", "request", "unlimited", 5, -1L);
        store.save(first, "agent", "request", "finite", 4, 20L);
        assertEquals(
                Long.valueOf(-1),
                store.load(first, "agent", "request").orElseThrow().grantedCalls());
        assertThrows(
                IllegalArgumentException.class,
                () -> store.save(first, "agent", "request", "bad", -1, 2L));
        assertThrows(
                IllegalArgumentException.class,
                () -> store.save(first, "agent", "request", "bad", 1, -2L));
        store.deleteSession(first.toString());
        assertTrue(store.load(first, "agent", "request").isEmpty());
        assertTrue(store.load(first, "other", "request").isEmpty());
        assertEquals("second", store.load(second, "agent", "request").orElseThrow().task());
    }
}
