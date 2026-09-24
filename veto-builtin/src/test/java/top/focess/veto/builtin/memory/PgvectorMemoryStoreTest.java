package top.focess.veto.builtin.memory;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import top.focess.veto.builtin.memory.embedder.HashEmbedder;

/** SQL contract tests. These do not claim execution against PostgreSQL or its vector extension. */
class PgvectorMemoryStoreTest {
    @Test
    void sessionAndProjectPredicatesPrecedeCandidateLimit() {
        var database = mock(EntityManager.class);
        var query = mock(Query.class, RETURNS_SELF);
        when(database.createNativeQuery(anyString())).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of());
        var user = UUID.randomUUID();
        var session = UUID.randomUUID();
        var project = UUID.randomUUID();
        new PgvectorMemoryStore(database, new HashEmbedder())
                .search(
                        new MemoryQuery(
                                "query",
                                List.of(MemoryTier.SESSION),
                                session,
                                project,
                                user,
                                5,
                                .5f));
        var sql = ArgumentCaptor.forClass(String.class);
        verify(database).createNativeQuery(sql.capture());
        assertTrue(
                sql.getValue().indexOf("session_id = :session") < sql.getValue().indexOf("LIMIT"));
        assertTrue(
                sql.getValue().indexOf("project_id = :project") < sql.getValue().indexOf("LIMIT"));
        verify(query).setParameter("session", session.toString());
        verify(query).setParameter("project", project.toString());
        verify(query).setParameter("uid", user.toString());
    }

    @Test
    void promotionKeepsStoredVectorAndBindsOwnerBeforeReading() {
        var database = mock(EntityManager.class);
        var select = mock(Query.class, RETURNS_SELF);
        var delete = mock(Query.class, RETURNS_SELF);
        var insert = mock(Query.class, RETURNS_SELF);
        when(database.createNativeQuery(startsWith("SELECT"))).thenReturn(select);
        when(database.createNativeQuery(startsWith("DELETE"))).thenReturn(delete);
        when(database.createNativeQuery(startsWith("INSERT"))).thenReturn(insert);
        UUID user = UUID.randomUUID();
        var id = MemoryId.random();
        @Nullable Object @NonNull [] row = {
            id.value().toString(),
            user.toString(),
            UUID.randomUUID().toString(),
            "SESSION",
            null,
            "legacy text",
            "insight",
            Timestamp.from(Instant.now()),
            "[0.25,0.75]"
        };
        when(select.getResultList()).thenReturn(java.util.Collections.singletonList(row));
        when(delete.executeUpdate()).thenReturn(1);
        var promoted = new PgvectorMemoryStore(database, new HashEmbedder()).promote(id, user);
        if (promoted == null) throw new AssertionError("Expected promotion to return the new id");
        assertNotEquals(id, promoted);
        verify(select).setParameter("userId", user.toString());
        verify(insert).setParameter("vec", "[0.25,0.75]");
        verify(insert).setParameter("tier", "CROSS_SESSION");
        assertTrue(
                mockingDetails(insert).getInvocations().stream()
                        .anyMatch(
                                invocation ->
                                        invocation.getMethod().getName().equals("setParameter")
                                                && invocation.getArguments().length == 2
                                                && "sid".equals(invocation.getArguments()[0])
                                                && invocation.getArguments()[1] == null));
    }
}
