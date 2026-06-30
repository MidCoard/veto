package top.focess.veto.memory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

/**
 * A simple in-memory vector index (the production path is pgvector; this is the MVP reference that
 * gives us the abstraction in place). Uses brute-force cosine similarity for now — a real HNSW
 * implementation would bucket vectors by approximate nearest neighbors for sub-linear search. For
 * the MVP the corpus size is small enough that brute force is acceptable.
 *
 * <p>The index is thread-safe: a single {@link ReentrantReadWriteLock} guards the vector array.
 * Reads (search) are concurrent; writes (insert) are exclusive.
 *
 * <p>Part 4.3: the production path uses pgvector's HNSW index for scalable similarity search; this
 * in-memory backend is the fallback + the reference implementation.
 */
@Component
public class VectorIndex {

    private final ConcurrentMap<UUID, float[]> vectors = new ConcurrentHashMap<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    /** Insert a vector at the given id. Replaces any existing vector at that id. */
    public void insert(@NonNull UUID id, float[] vector) {
        lock.writeLock().lock();
        try {
            vectors.put(id, vector == null ? new float[0] : vector.clone());
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Remove a vector by id. No-op if not present. */
    public void remove(@NonNull UUID id) {
        lock.writeLock().lock();
        try {
            vectors.remove(id);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Brute-force cosine-similarity search. Returns the top-K (id, score) pairs ranked by
     * descending score. Vectors with zero norm (or zero overlap with the query) are skipped.
     */
    public @NonNull List<Match> topK(float[] query, int k) {
        if (query == null || query.length == 0 || k <= 0) {
            return List.of();
        }
        lock.readLock().lock();
        try {
            List<Match> matches = new ArrayList<>();
            for (var entry : vectors.entrySet()) {
                float score = cosineSimilarity(query, entry.getValue());
                if (score > 0f) {
                    matches.add(new Match(entry.getKey(), score));
                }
            }
            matches.sort(Comparator.comparingDouble(Match::score).reversed());
            if (matches.size() > k) {
                return matches.subList(0, k);
            }
            return matches;
        } finally {
            lock.readLock().unlock();
        }
    }

    public int size() {
        return vectors.size();
    }

    private static float cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || b.length == 0) {
            return 0f;
        }
        int n = Math.min(a.length, b.length);
        float dot = 0f, na = 0f, nb = 0f;
        for (int i = 0; i < n; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0f || nb == 0f) {
            return 0f;
        }
        return dot / ((float) Math.sqrt(na) * (float) Math.sqrt(nb));
    }

    /** A single search result: the memory id + cosine similarity score. */
    public record Match(@NonNull UUID id, float score) {}
}
