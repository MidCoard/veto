package top.focess.veto.agent.capability;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.tool.ToolCallContext;
import top.focess.veto.agent.tool.ToolCapability;
import top.focess.veto.agent.tool.ToolErrors;
import top.focess.veto.memory.Memory;
import top.focess.veto.memory.MemoryQuery;
import top.focess.veto.memory.MemoryStore;
import top.focess.veto.memory.MemoryTier;
import top.focess.veto.memory.MemoryTools.RecallMemory;

@Component
public final class MemoryReadCapabilityImpl implements MemoryReadCapability {
    private static final int MAX_QUERY_CHARS = 4000;
    private static final int RECALL_RESULT_LIMIT = 5;
    private static final float RECALL_SCORE_FLOOR = 0.5f;
    private final @NonNull MemoryStore store;

    public MemoryReadCapabilityImpl(@NonNull MemoryStore store) {
        this.store = store;
    }

    @Override
    public @NonNull String recall(RecallMemory.@NonNull Args args) {
        ToolCallContext ctx =
                CapabilityAccess.require(ToolCapability.MEMORY_READ, "recall_memory", args);
        String query = boundedQuery(args.query());
        UUID sessionId = ctx.sessionId();
        if (sessionId == null) {
            try {
                sessionId = UUID.fromString(ctx.agentId());
            } catch (IllegalArgumentException e) {
                return ToolErrors.failure("no session context; memories not recalled");
            }
        }
        MemoryQuery sessionQuery =
                new MemoryQuery(
                        query,
                        List.of(MemoryTier.SESSION),
                        sessionId,
                        null,
                        ctx.userId(),
                        RECALL_RESULT_LIMIT,
                        RECALL_SCORE_FLOOR);
        MemoryQuery crossSessionQuery =
                new MemoryQuery(
                        query,
                        List.of(MemoryTier.CROSS_SESSION),
                        null,
                        null,
                        ctx.userId(),
                        RECALL_RESULT_LIMIT,
                        RECALL_SCORE_FLOOR);
        List<MemoryStore.ScoredMemory> matches = new ArrayList<>(RECALL_RESULT_LIMIT * 2);
        matches.addAll(store.search(sessionQuery));
        matches.addAll(store.search(crossSessionQuery));
        matches.sort(Comparator.comparingDouble(MemoryStore.ScoredMemory::score).reversed());
        if (matches.size() > RECALL_RESULT_LIMIT) {
            matches = new ArrayList<>(matches.subList(0, RECALL_RESULT_LIMIT));
        }
        return formatResults(matches);
    }

    public static @NonNull String formatResults(@NonNull List<MemoryStore.ScoredMemory> results) {
        if (results.isEmpty()) {
            return "no matching memories";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(results.size()).append(" memories:\n");
        for (MemoryStore.ScoredMemory sm : results) {
            Memory m = sm.memory();
            Memory.SourceRef sourceRef = m.sourceRef();
            sb.append("- [")
                    .append(m.tier())
                    .append("] id=")
                    .append(m.id().value())
                    .append(" score=")
                    .append(String.format(Locale.ROOT, "%.3f", sm.score()))
                    .append(" src=")
                    .append(sourceRef == null ? "unknown" : sourceRef.kind())
                    .append(" ")
                    .append(sourceRef == null ? Map.of() : sourceRef.attrs())
                    .append("\n");
            String content = m.content();
            if (content.length() > 240) {
                content = content.substring(0, 240) + "...";
            }
            sb.append("  ").append(content).append("\n");
        }
        return sb.toString();
    }

    private static @NonNull String boundedQuery(@NonNull String query) {
        return query.length() <= MAX_QUERY_CHARS ? query : query.substring(0, MAX_QUERY_CHARS);
    }
}
