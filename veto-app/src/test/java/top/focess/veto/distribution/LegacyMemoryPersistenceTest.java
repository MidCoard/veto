package top.focess.veto.distribution;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;
import top.focess.veto.builtin.memory.JpaMemoryStore;
import top.focess.veto.builtin.memory.MemoryEntity;
import top.focess.veto.builtin.memory.MemoryId;
import top.focess.veto.builtin.memory.MemoryQuery;
import top.focess.veto.builtin.memory.MemoryRepository;
import top.focess.veto.builtin.memory.MemoryTier;
import top.focess.veto.builtin.memory.embedder.Embedder;

@DataJpaTest(properties = "veto.observability.audit-log-path=${java.io.tmpdir}/veto-test-audit")
@ContextConfiguration(classes = LegacyMemoryPersistenceTest.Configuration.class)
class LegacyMemoryPersistenceTest {
    @SpringBootConfiguration
    @EntityScan(basePackageClasses = MemoryEntity.class)
    @EnableJpaRepositories(basePackageClasses = MemoryRepository.class)
    static class Configuration {}

    private final @NonNull EntityManager database;
    private final @NonNull MemoryRepository repository;

    @Autowired
    LegacyMemoryPersistenceTest(
            @NonNull EntityManager database, @NonNull MemoryRepository repository) {
        this.database = database;
        this.repository = repository;
    }

    @Test
    void existingRowKeepsIdAttributionVectorAndOwnerIsolation() {
        var user = UUID.nameUUIDFromBytes("legacy-owner".getBytes(StandardCharsets.UTF_8));
        var other = UUID.randomUUID();
        var id = MemoryId.random();
        var session = UUID.randomUUID();
        database.createNativeQuery(
                        "INSERT INTO memories (id,user_id,session_id,tier,content,embedding,source_ref,created_at) VALUES (:id,:user,:session,'SESSION','existing memory','1.0,0.0','turn_range {from=12, to=12}',CURRENT_TIMESTAMP)")
                .setParameter("id", id.value().toString())
                .setParameter("user", user.toString())
                .setParameter("session", session.toString())
                .executeUpdate();
        Embedder embedding =
                new Embedder() {
                    public float @NonNull [] embed(@NonNull String text) {
                        return new float[] {1, 0};
                    }

                    public int dimension() {
                        return 2;
                    }
                };
        var store = new JpaMemoryStore(repository, embedding);
        var rows =
                store.search(
                        new MemoryQuery(
                                "existing",
                                List.of(MemoryTier.SESSION),
                                session,
                                null,
                                user,
                                5,
                                .5f));
        assertEquals(1, rows.size());
        assertEquals(id, rows.getFirst().memory().id());
        var source = rows.getFirst().memory().sourceRef();
        if (source == null) throw new AssertionError("Legacy source attribution must survive");
        assertEquals("turn_range {from=12, to=12}", source.attrs().get("raw"));
        assertNull(store.promote(id, other));
        assertFalse(store.forget(id, other));
        assertTrue(
                store.search(
                                new MemoryQuery(
                                        "existing",
                                        List.of(MemoryTier.SESSION),
                                        session,
                                        null,
                                        other,
                                        5,
                                        .5f))
                        .isEmpty());
        MemoryId promoted = store.promote(id, user);
        if (promoted == null) throw new AssertionError("Expected owned promotion");
        assertFalse(repository.existsById(id.value().toString()));
        var memory =
                MemoryEntity.toMemory(
                        repository.findById(promoted.value().toString()).orElseThrow());
        assertNull(memory.sessionId());
        assertEquals(MemoryTier.CROSS_SESSION, memory.tier());
        assertArrayEquals(new float[] {1, 0}, memory.embedding());
        assertTrue(store.forget(promoted, user));
    }
}
