package top.focess.veto.builtin.memory;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.veto.builtin.memory.embedder.Embedder;

class VolatileMemoryRankingTest {
    private static final @NonNull Embedder EMBEDDER =
            new Embedder() {
                public float @NonNull [] embed(@NonNull String text) {
                    return new float[] {1, 0};
                }

                public int dimension() {
                    return 2;
                }
            };

    @Test
    void bothVolatileProfilesRankOnlyEligibleMemoriesEvenBeyondTheFormerGlobalWindow() {
        var userId = UUID.randomUUID();
        var other = UUID.randomUUID();
        var session = UUID.randomUUID();
        var project = UUID.randomUUID();
        for (boolean vectorProfile : List.of(false, true)) {
            var store = new InMemoryMemoryStore(EMBEDDER, vectorProfile);
            for (int i = 0; i < 100; i++)
                store.add(memory(other, session, project, new float[] {1, 0}));
            store.add(memory(userId, UUID.randomUUID(), project, new float[] {1, 0}));
            store.add(memory(userId, session, UUID.randomUUID(), new float[] {1, 0}));
            var target = memory(userId, session, project, new float[] {.8f, .6f});
            store.add(target);
            var matches =
                    store.search(
                            new MemoryQuery(
                                    "query",
                                    List.of(MemoryTier.SESSION),
                                    session,
                                    project,
                                    userId,
                                    1,
                                    .5f));
            assertEquals(List.of(target.id()), matches.stream().map(m -> m.memory().id()).toList());
            assertEquals(.8f, matches.getFirst().score(), .0001f);
            assertTrue(
                    store.search(
                                    new MemoryQuery(
                                            "query",
                                            List.of(MemoryTier.SESSION),
                                            session,
                                            project,
                                            userId,
                                            1,
                                            .9f))
                            .isEmpty());
            assertTrue(
                    store.search(
                                    new MemoryQuery(
                                            "query",
                                            List.of(MemoryTier.CROSS_SESSION),
                                            null,
                                            null,
                                            userId,
                                            1,
                                            0))
                            .isEmpty());
        }
    }

    @Test
    void profileSpecificZeroScorePolicyRemainsUnchanged() {
        var userId = UUID.randomUUID();
        var session = UUID.randomUUID();
        var zero = memory(userId, session, null, new float[] {0, 1});
        var query =
                new MemoryQuery("query", List.of(MemoryTier.SESSION), session, null, userId, 1, 0);
        var memory = new InMemoryMemoryStore(EMBEDDER);
        var vector = new InMemoryMemoryStore(EMBEDDER, true);
        memory.add(zero);
        vector.add(zero);
        assertEquals(1, memory.search(query).size());
        assertTrue(vector.search(query).isEmpty());
    }

    private static @NonNull Memory memory(
            @NonNull UUID userId,
            @NonNull UUID session,
            UUID project,
            float @NonNull [] embedding) {
        return new Memory(
                MemoryId.random(),
                userId,
                session,
                MemoryTier.SESSION,
                project,
                "fact",
                embedding,
                null,
                Instant.EPOCH);
    }
}
