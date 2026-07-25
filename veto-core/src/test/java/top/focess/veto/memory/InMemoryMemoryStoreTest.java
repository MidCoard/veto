package top.focess.veto.memory;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import top.focess.veto.agent.TurnRecord;
import top.focess.veto.memory.embedder.HashEmbedder;

class InMemoryMemoryStoreTest {

    private final HashEmbedder embedder = new HashEmbedder();
    private InMemoryMemoryStore store;
    private UUID alice;
    private UUID bob;
    private UUID sessionId;

    @BeforeEach
    void setUp() {
        store = new InMemoryMemoryStore(embedder);
        alice = UUID.randomUUID();
        bob = UUID.randomUUID();
        sessionId = UUID.randomUUID();
    }

    @Test
    void addAndSearch() {
        MemoryId id = store.add(buildMemory("the quick brown fox", MemoryTier.CROSS_SESSION));
        List<MemoryStore.ScoredMemory> results =
                store.search(MemoryQuery.crossSession("quick fox", alice));
        assertEquals(1, results.size());
        assertEquals(id, results.get(0).memory().id());
    }

    @Test
    void tenantIsolation() {
        store.add(buildMemory("alice's private insight", MemoryTier.CROSS_SESSION));
        List<MemoryStore.ScoredMemory> aliceResults =
                store.search(MemoryQuery.crossSession("alice insight", alice));
        List<MemoryStore.ScoredMemory> bobResults =
                store.search(MemoryQuery.crossSession("alice insight", bob));
        assertEquals(1, aliceResults.size());
        assertEquals(0, bobResults.size(), "Bob must not see Alice's memories");
    }

    @Test
    void scoreFloorFiltersLowSimilarity() {
        store.add(
                buildMemory("completely different text about gardening", MemoryTier.CROSS_SESSION));
        List<MemoryStore.ScoredMemory> results =
                store.search(
                        new MemoryQuery(
                                "fox jumping over dog",
                                List.of(MemoryTier.CROSS_SESSION),
                                null,
                                null,
                                alice,
                                5,
                                0.99f));
        assertEquals(0, results.size(), "Low-similarity results must be filtered by score floor");
    }

    @Test
    void topKLimitsResults() {
        for (int i = 0; i < 10; i++) {
            store.add(buildMemory("fox " + i, MemoryTier.CROSS_SESSION));
        }
        List<MemoryStore.ScoredMemory> results =
                store.search(
                        new MemoryQuery(
                                "fox",
                                List.of(MemoryTier.CROSS_SESSION),
                                null,
                                null,
                                alice,
                                3,
                                0f));
        assertEquals(3, results.size());
    }

    @Test
    void promoteStripsSessionId() {
        MemoryId id = store.add(buildMemory("a session-private insight", MemoryTier.SESSION));
        store.promote(id);
        assertEquals(1, store.size(), "After promote, exactly one memory remains");
        // Verify the surviving memory is CROSS_SESSION tier with sessionId stripped.
        for (Memory m : store.snapshot().values()) {
            assertEquals(MemoryTier.CROSS_SESSION, m.tier());
            assertNull(m.sessionId());
        }
    }

    @Test
    void forgetRemovesMemory() {
        MemoryId id = store.add(buildMemory("to be forgotten", MemoryTier.CROSS_SESSION));
        assertEquals(1, store.size());
        store.forget(id);
        assertEquals(0, store.size());
    }

    @Test
    void captureTurnStoresMaskedContent() {
        TurnRecord turn =
                TurnRecord.toolResponse(1, "call-abc", "the secret is password=hunter2", true);
        store.capture(turn, sessionId, alice);
        assertEquals(1, store.size());
        Memory m = store.snapshot().values().iterator().next();
        assertEquals(sessionId, m.sessionId());
        assertEquals(alice, m.userId());
        assertEquals(MemoryTier.SESSION, m.tier());
        assertTrue(m.content().contains("password=hunter2"));
    }

    @Test
    void identicalTextsScoreHigh() {
        store.add(buildMemory("foo bar baz", MemoryTier.CROSS_SESSION));
        List<MemoryStore.ScoredMemory> results =
                store.search(
                        new MemoryQuery(
                                "foo bar baz",
                                List.of(MemoryTier.CROSS_SESSION),
                                null,
                                null,
                                alice,
                                5,
                                0f));
        assertEquals(1, results.size());
        assertTrue(results.get(0).score() > 0.99f, "Identical text must score ~1.0");
    }

    @Test
    void embeddingIsDeterministic() {
        float[] a = embedder.embed("hello world");
        float[] b = embedder.embed("hello world");
        assertEquals(HashEmbedder.DIMENSION, a.length);
        for (int i = 0; i < a.length; i++) {
            assertEquals(a[i], b[i], 1e-6f);
        }
    }

    private Memory buildMemory(String content, MemoryTier tier) {
        return new Memory(
                MemoryId.random(),
                alice,
                tier == MemoryTier.SESSION ? sessionId : null,
                tier,
                null,
                content,
                embedder.embed(content),
                Memory.SourceRef.insightOrigin("test"),
                java.time.Instant.now());
    }
}
