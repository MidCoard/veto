package top.focess.veto.memory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

/**
 * A Hierarchical Navigable Small World (HNSW) index — approximate nearest neighbor search with
 * sub-linear query time. The reference implementation for the pgvector production path.
 *
 * <p>HNSW builds a multi-layer graph: layer 0 is the densest (every node is present); higher layers
 * are sparser (a subset of nodes with longer "express" edges). Search starts at the top layer's
 * entry point, greedily descends by similarity, then refines in lower layers. The result is
 * approximately the same as brute force but with much better query time.
 *
 * <p>This is a simplified HNSW. The full paper (Malkov & Yashunin 2018) tunes {@code M}, {@code
 * efConstruction}, {@code efSearch}, and the neighbor-selection heuristic. We use modest defaults
 * ({@code M=8}, {@code efConstruction=64}, {@code efSearch=32}) which are sufficient for the MVP
 * corpus size.
 */
@Component
public class HnswIndex {

    private static final int M = 8; // max neighbors per node per layer
    private static final int M0 = 8; // max neighbors at layer 0 (typically 2*M)
    private static final int EF_CONSTRUCTION = 64;
    private static final int EF_SEARCH = 32;
    private static final double ML = 1.0 / Math.log(M);

    private final @NonNull ConcurrentMap<UUID, Node> nodes = new ConcurrentHashMap<>();
    private final @NonNull ConcurrentMap<UUID, float[]> vectors = new ConcurrentHashMap<>();
    private final @NonNull ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final @NonNull Random random = new Random();

    /** The top layer's entry point (a node id); null if the index is empty. */
    private volatile UUID entryPoint;

    /** Insert a vector at the given id. Replaces any existing vector at that id. */
    public void insert(@NonNull UUID id, float[] vector) {
        if (vector == null || vector.length == 0) {
            return;
        }
        float[] v = vector.clone();
        lock.writeLock().lock();
        try {
            vectors.put(id, v);
            int targetLevel = randomLevel();
            Node node = new Node(id, targetLevel);
            nodes.put(id, node);
            UUID current = entryPoint;
            if (current == null) {
                entryPoint = id;
                return;
            }
            float dist = distance(v, vectors.get(current));
            int currentMaxLevel = maxLevel();
            // Phase 1: greedy search from the current top layer down to min(targetLevel,
            // currentMaxLevel)+1.
            // The loop body is a no-op when targetLevel >= currentMaxLevel (no upper layers to
            // search) — that's the common "first high-level insert" case.
            for (int level = currentMaxLevel; level > targetLevel; level--) {
                List<SearchResult> neighbors =
                        searchLayer(v, List.of(new SearchResult(current, dist)), level);
                if (neighbors.isEmpty()) {
                    break;
                }
                current = neighbors.get(0).id;
                dist = distance(v, vectors.get(current));
            }
            // Phase 2: insert at every layer the new node claims (0..targetLevel). Previously
            // this only ran 0..min(targetLevel, currentMaxLevel), which left the new node with
            // zero edges at its top layers when targetLevel > currentMaxLevel and collapsed
            // search to a single-path descent on the new entry point.
            for (int level = Math.min(targetLevel, currentMaxLevel); level >= 0; level--) {
                insertAtLayer(node, v, level, current);
            }
            for (int level = currentMaxLevel + 1; level <= targetLevel; level++) {
                // New layers above the previous top: the new node has no peers yet, so the
                // greedy search starts at itself. We still set up the neighbor set so future
                // inserts at this layer can find it.
                node.neighborsByLevel.put(level, new HashSet<>());
            }
            if (targetLevel > currentMaxLevel) {
                entryPoint = id;
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Phase-2 step: at the given layer, search from the current greedy-descent point, pick the
     * closest {@code M} (or {@code M0} at layer 0), wire bidirectional edges, and prune the peer's
     * list if it grew too large.
     */
    private void insertAtLayer(
            @NonNull Node node, float @NonNull [] v, int level, @NonNull UUID entryCurrent) {
        float[] curVec = vectors.get(entryCurrent);
        float dist = curVec == null ? Float.POSITIVE_INFINITY : distance(v, curVec);
        List<SearchResult> candidates =
                searchLayer(v, List.of(new SearchResult(entryCurrent, dist)), level);
        List<SearchResult> selected = selectNeighbors(candidates, level == 0 ? M0 : M);
        Set<UUID> myNeighbors = node.neighborsByLevel.computeIfAbsent(level, k -> new HashSet<>());
        for (SearchResult r : selected) {
            myNeighbors.add(r.id);
            Node other = nodes.get(r.id);
            if (other != null) {
                Set<UUID> otherNeighbors =
                        other.neighborsByLevel.computeIfAbsent(level, k -> new HashSet<>());
                otherNeighbors.add(node.id);
                if (otherNeighbors.size() > (level == 0 ? M0 : M)) {
                    pruneNeighbors(other, level);
                }
            }
        }
    }

    /** Remove a vector by id. No-op if not present. */
    public void remove(@NonNull UUID id) {
        lock.writeLock().lock();
        try {
            vectors.remove(id);
            Node n = nodes.remove(id);
            if (n == null) {
                return;
            }
            for (var entry : n.neighborsByLevel.entrySet()) {
                for (UUID other : entry.getValue()) {
                    Node o = nodes.get(other);
                    if (o != null && o.neighborsByLevel.containsKey(entry.getKey())) {
                        o.neighborsByLevel.get(entry.getKey()).remove(id);
                    }
                }
            }
            if (id.equals(entryPoint)) {
                // Recompute entry point: pick the highest-level remaining node.
                UUID newEntry = null;
                int bestLevel = -1;
                for (var entry : nodes.entrySet()) {
                    int top = maxLevelOf(entry.getValue());
                    if (top > bestLevel) {
                        bestLevel = top;
                        newEntry = entry.getKey();
                    }
                }
                entryPoint = newEntry;
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Approximate k-nearest-neighbor search. Returns up to {@code k} (id, score) pairs ranked by
     * descending similarity (higher = closer).
     */
    public @NonNull List<VectorIndex.Match> topK(float[] query, int k) {
        if (query == null || query.length == 0 || k <= 0) {
            return List.of();
        }
        lock.readLock().lock();
        try {
            UUID current = entryPoint;
            if (current == null) {
                return List.of();
            }
            float dist = distance(query, vectors.get(current));
            // Phase 1: greedy descent from the top layer down to layer 0.
            for (int level = maxLevel(); level > 0; level--) {
                List<SearchResult> r =
                        searchLayer(query, List.of(new SearchResult(current, dist)), level);
                if (!r.isEmpty()) {
                    current = r.get(0).id;
                    dist = distance(query, vectors.get(current));
                }
            }
            // Phase 2: efSearch-candidate beam search at layer 0.
            List<SearchResult> result =
                    searchLayer(query, List.of(new SearchResult(current, dist)), 0);
            result.sort(Comparator.comparingDouble((@NonNull SearchResult sr) -> -sr.score));
            List<VectorIndex.Match> out = new ArrayList<>();
            for (int i = 0; i < Math.min(k, result.size()); i++) {
                SearchResult sr = result.get(i);
                if (sr.score > 0f) {
                    out.add(new VectorIndex.Match(sr.id, sr.score));
                }
            }
            return out;
        } finally {
            lock.readLock().unlock();
        }
    }

    public int size() {
        return vectors.size();
    }

    private int randomLevel() {
        // Standard HNSW level sampling: -ln(uniform(0,1)) * ML.
        return (int) Math.floor(-Math.log(random.nextDouble()) * ML);
    }

    private int maxLevel() {
        int m = -1;
        for (Node n : nodes.values()) {
            m = Math.max(m, maxLevelOf(n));
        }
        return m;
    }

    private static int maxLevelOf(@NonNull Node n) {
        int m = -1;
        for (Integer l : n.neighborsByLevel.keySet()) {
            m = Math.max(m, l);
        }
        return m;
    }

    /**
     * Single-layer HNSW search: maintain a candidate set + a visited set; iteratively expand the
     * best candidate, add its neighbors, prune the worse ones.
     */
    private @NonNull List<SearchResult> searchLayer(
            float @NonNull [] query, @NonNull List<SearchResult> entryPoints, int level) {
        List<SearchResult> candidates = new ArrayList<>(entryPoints);
        candidates.sort(Comparator.comparingDouble((@NonNull SearchResult sr) -> -sr.score));
        List<SearchResult> result = new ArrayList<>(entryPoints);
        result.sort(Comparator.comparingDouble((@NonNull SearchResult sr) -> -sr.score));
        Set<UUID> visited = new HashSet<>();
        for (SearchResult ep : entryPoints) {
            visited.add(ep.id);
        }
        while (!candidates.isEmpty()) {
            SearchResult current = candidates.get(0);
            candidates.remove(0);
            float furthest =
                    result.isEmpty()
                            ? Float.NEGATIVE_INFINITY
                            : result.get(result.size() - 1).score;
            if (current.score < furthest) {
                break;
            }
            Node node = nodes.get(current.id);
            if (node == null) {
                continue;
            }
            Set<UUID> neighbors = node.neighborsByLevel.getOrDefault(level, Set.of());
            for (UUID n : neighbors) {
                if (visited.add(n)) {
                    float[] nv = vectors.get(n);
                    if (nv == null) {
                        continue;
                    }
                    float dist = distance(query, nv);
                    if (result.size() < EF_CONSTRUCTION || dist > furthest) {
                        SearchResult next = new SearchResult(n, dist);
                        candidates.add(next);
                        result.add(next);
                    }
                }
            }
            // Keep the best EF_CONSTRUCTION candidates.
            candidates.sort(Comparator.comparingDouble((@NonNull SearchResult sr) -> -sr.score));
            result.sort(Comparator.comparingDouble((@NonNull SearchResult sr) -> -sr.score));
            if (result.size() > EF_CONSTRUCTION) {
                result = new ArrayList<>(result.subList(0, EF_CONSTRUCTION));
            }
        }
        return result;
    }

    /**
     * Neighbor-selection heuristic: pick the {@code m} closest. A more advanced implementation
     * would use the "heuristic" rule from the HNSW paper; this is the simple variant.
     */
    private static @NonNull List<SearchResult> selectNeighbors(
            @NonNull List<SearchResult> candidates, int m) {
        candidates.sort(Comparator.comparingDouble((@NonNull SearchResult sr) -> -sr.score));
        return candidates.subList(0, Math.min(m, candidates.size()));
    }

    /**
     * Distance-based neighbor pruning: when a peer's neighbor list at this layer exceeds the budget
     * ({@code M} at upper layers, {@code M0} at layer 0), score every neighbor against the peer's
     * own vector and keep the closest {@code M} ({@code M0} at layer 0). The previous
     * implementation truncated by HashSet iteration order — which has no relation to distance — and
     * silently kept the FURTHEST neighbors while dropping the closest, regressing graph quality
     * with every prune.
     */
    private void pruneNeighbors(@NonNull Node node, int level) {
        int budget = level == 0 ? M0 : M;
        Set<UUID> neighbors = node.neighborsByLevel.get(level);
        if (neighbors == null || neighbors.size() <= budget) {
            return;
        }
        float[] myVec = vectors.get(node.id);
        if (myVec == null) {
            return;
        }
        // Score every current neighbor; keep the closest `budget`.
        List<SearchResult> scored = new ArrayList<>(neighbors.size());
        for (UUID n : neighbors) {
            float[] nv = vectors.get(n);
            if (nv == null) {
                continue;
            }
            scored.add(new SearchResult(n, distance(myVec, nv)));
        }
        scored.sort(Comparator.comparingDouble((@NonNull SearchResult sr) -> sr.score));
        Set<UUID> kept = new HashSet<>(budget);
        for (int i = 0; i < Math.min(budget, scored.size()); i++) {
            kept.add(scored.get(i).id);
        }
        node.neighborsByLevel.put(level, kept);
    }

    /** Squared L2 distance. (Cosine is computed at the higher layer for ranking.) */
    private static float distance(float[] a, float[] b) {
        if (a == null || b == null) {
            return Float.POSITIVE_INFINITY;
        }
        int n = Math.min(a.length, b.length);
        float sum = 0f;
        for (int i = 0; i < n; i++) {
            float d = a[i] - b[i];
            sum += d * d;
        }
        return sum;
    }

    /** A node in the HNSW graph: its neighbors per layer. */
    private static final class Node {
        final @NonNull UUID id;
        final @NonNull Map<Integer, Set<UUID>> neighborsByLevel = new java.util.HashMap<>();

        Node(@NonNull UUID id, int topLevel) {
            this.id = id;
            // Pre-allocate neighbor sets for layers 0..topLevel.
            for (int l = 0; l <= topLevel; l++) {
                neighborsByLevel.put(l, new HashSet<>());
            }
        }
    }

    /** A search result: the node id + similarity score. */
    private record SearchResult(@NonNull UUID id, float score) {}
}
