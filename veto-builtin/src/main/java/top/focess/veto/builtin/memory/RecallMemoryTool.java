package top.focess.veto.builtin.memory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.tool.Doc;
import top.focess.veto.api.agent.tool.ParamCategory;
import top.focess.veto.api.agent.tool.SecurityHint;
import top.focess.veto.api.agent.tool.ToolDoc;
import top.focess.veto.api.agent.tool.ToolResultFormat;

/** {@code recall_memory} — search current-session memories and cross-session insights. */
@ToolDoc(
        resultFormats = {ToolResultFormat.PLAINTEXT},
        description =
                """
                Search the current session's captured memory and the user's cross-session insights together.\
                """,
        behavior =
                """
                Embeds `query`, searches both the current session and the user's cross-session insights, \
                combines the matches, and returns the 5 highest-scoring results above the 0.5 similarity \
                threshold. `query` is capped at 4000 characters. Every result identifies its tier and source \
                and includes a content snippet of at most 240 characters.\
                """,
        whenToUse =
                """
                Use `recall_memory` when relevant information is no longer in the active context: earlier \
                decisions or observations from this session, or reusable knowledge saved from previous \
                sessions.\
                """,
        whenNotToUse =
                """
                - Do not use it when the information is still in your active context - just reference it \
                directly. - Do not use it as a substitute for `view_file` or `grep_search` for finding code.\
                """,
        resultContract =
                """
                Plain text beginning `<count> memories:`, followed by bullet entries containing tier, id, \
                score, source, and a content snippet. No match returns `no matching memories`. Missing \
                session context (failure, NO_SESSION_CONTEXT): `No session context: memories were not \
                recalled.`\
                """,
        errorsAndEdgeCases =
                """
                An unknown query or empty memory stores can legitimately yield zero matches. Refine the query \
                before retrying; never invent absent memories.\
                """,
        security =
                """
                Session results belong to the current session; all results belong to the current user.\
                """,
        examples = {
            "{\"query\": \"UserService authentication\"}",
            "{\"query\": \"build configuration\"}",
            "{\"query\": \"quick brown fox\"}",
            "{\"query\": \"deployment rollback procedure\"}"
        },
        returnExamples = {
            "2 memories:\n- [CROSS_SESSION] id=123e4567-e89b-12d3-a456-426614174000 score=0.880 src=INSIGHT {}\n  Prefer constructor injection over field injection...\n- [SESSION] id=123e4567-e89b-12d3-a456-426614174001 score=0.820 src=turn_range {from=12, to=12}\n  UserService.authenticate validates the JWT expiry and...",
            "1 memories:\n- [CROSS_SESSION] id=123e4567-e89b-12d3-a456-426614174002 score=0.910 src=INSIGHT {}\n  This project uses Gradle 8.5 with Kotlin DSL...",
            "1 memories:\n- [CROSS_SESSION] id=ffde62f9-716f-41e4-bcec-8d63fbf8ed7c score=0.900 src=stored {raw=insight_origin {origin=write_memory}}\n  test placeholder\n",
            "no matching memories"
        })
public final class RecallMemoryTool extends MemoryReadTool<RecallMemoryTool.Args> {

    private final MemoryReadCapability capability;

    /** Declaration-only instance; the host supplies the capability at execution time. */
    public RecallMemoryTool() {
        this.capability = null;
    }

    /** Creates an instance bound to the given host capability. */
    public RecallMemoryTool(@NonNull MemoryReadCapability capability) {
        this.capability = capability;
    }

    /** Model-facing arguments of {@code recall_memory}. */
    public record Args(
            @SecurityHint(ParamCategory.GENERIC) @Doc("Free-text query to embed + search.")
                    @NonNull String query) {}

    @Override
    public @NonNull String getName() {
        return "recall_memory";
    }

    @Override
    public @NonNull Class<Args> getArgsClass() {
        return Args.class;
    }

    @Override
    public @NonNull MemoryReadCapability memoryReadCapability() {
        if (capability == null) throw new SecurityException("Host must supply tool capability");
        return capability;
    }

    @Override
    public @NonNull String execute(@NonNull Args args, @NonNull MemoryReadCapability capability) {
        String query =
                args.query().length() <= 4000 ? args.query() : args.query().substring(0, 4000);
        List<ScoredMemory> matches = new ArrayList<>();
        matches.addAll(capability.search(query, MemoryTier.SESSION, 5, 0.5f));
        matches.addAll(capability.search(query, MemoryTier.CROSS_SESSION, 5, 0.5f));
        matches.sort(Comparator.comparingDouble(ScoredMemory::score).reversed());
        return formatResults(matches.subList(0, Math.min(5, matches.size())));
    }

    public static @NonNull String formatResults(@NonNull List<ScoredMemory> results) {
        if (results.isEmpty()) {
            return "no matching memories";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(results.size()).append(" memories:\n");
        for (ScoredMemory sm : results) {
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
}
