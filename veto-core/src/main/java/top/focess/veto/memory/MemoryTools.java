package top.focess.veto.memory;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.mcp.AgentTool;
import top.focess.veto.agent.mcp.Doc;
import top.focess.veto.agent.mcp.ParamCategory;
import top.focess.veto.agent.mcp.SecurityHint;
import top.focess.veto.agent.mcp.ToolCallContext;
import top.focess.veto.agent.mcp.ToolCallContextHolder;
import top.focess.veto.agent.mcp.ToolDoc;
import top.focess.veto.memory.embedder.Embedder;

/**
 * The agent-facing memory tools (long_term_memory_tiers.md §6). These are agent tools — they carry
 * {@link top.focess.veto.agent.mcp.RiskCategory#AGENT}; the Gateway returns {@code NotScreened}.
 * They still flow through the LoopInterceptor chain for audit.
 */
public final class MemoryTools {

    private MemoryTools() {}

    /** {@code recall_session} — search captured chunks from the current Session LTM. */
    @Component
    @ToolDoc(
            description =
                    "Search the current session's captured long-term memory (Session LTM) "
                            + "for relevant context.",
            usage =
                    """
                    #### When to use
                    Use `recall_session` when you need to recover context from earlier in this session \
                    - a previous tool result, a decision made, or an observation that is no longer in \
                    the active context window. The vector search returns the most similar memories \
                    ranked by embedding distance.

                    #### When NOT to use
                    - Do not use `recall_session` for cross-session knowledge - use `recall_insights`.
                    - Do not use it when the information is still in your active context - just \
                    reference it directly.
                    - Do not use it as a substitute for `view_file` or `grep_search` for finding code.

                    #### Behavior
                    Embeds `query` using the local embedding model, performs cosine similarity search \
                    in Session LTM, filters results by `scoreFloor`, and returns the top-K matches \
                    ranked by similarity. Source attribution is included.

                    #### Return format
                    A numbered list of memories, each with id, score, source type, and content snippet.

                    #### Errors & edge cases
                    If `scoreFloor` is too high, returns empty (not hallucinated matches). Empty query \
                    returns empty. Fuzzy matching prevents exact-match limitations. If no memories \
                    exist for this session, returns empty.

                    #### Security
                    Agent tool (`RiskCategory.AGENT`). The Gateway does not screen it. Tenant \
                    isolation enforced - the agent can only see memories belonging to its user. \
                    Safe to call any time.
                    """,
            examples = {
                "{\"query\": \"UserService authentication\"}",
                "{\"query\": \"build configuration\", \"topK\": 3, \"scoreFloor\": 0.6}"
            },
            returnExamples = {
                "1. [0.82] (tool_result) UserService.authenticate validates the JWT expiry and...\n"
                        + "2. [0.74] (observation) Decided to reject expired tokens with 401...",
                "(no matches)"
            })
    public static final class RecallSession implements AgentTool<RecallSession.Args> {

        private final MemoryStore store;

        public RecallSession(MemoryStore store) {
            this.store = store;
        }

        public record Args(
                @SecurityHint(ParamCategory.GENERIC) @Doc("Free-text query to embed + search.")
                        String query,
                @Nullable @Doc("Optional top-K; defaults to 5.") Integer topK,
                @Nullable @Doc("Optional score floor in [0,1]; defaults to 0.5.")
                        Float scoreFloor) {}

        @Override
        public String getName() {
            return "recall_session";
        }

        @Override
        public String getDescription() {
            return "Search the current session's captured long-term memory (Session LTM) "
                    + "for relevant context.";
        }

        @Override
        public Class<Args> getArgsClass() {
            return Args.class;
        }

        @Override
        public @NonNull String execute(@NonNull Args args) {
            ToolCallContext ctx = ToolCallContextHolder.get();
            if (ctx == null) {
                return "no matching memories"; // no session context available
            }
            String query = args.query() == null ? "" : args.query();
            int topK = args.topK() != null && args.topK() > 0 ? args.topK() : 5;
            float scoreFloor = args.scoreFloor() != null ? clamp01(args.scoreFloor()) : 0.5f;
            UUID sessionId = UUID.fromString(ctx.agentId());
            MemoryQuery q =
                    new MemoryQuery(
                            query,
                            List.of(MemoryTier.SESSION),
                            sessionId,
                            null,
                            ctx.userId(),
                            topK,
                            scoreFloor);
            return formatResults(store.search(q));
        }
    }

    /** {@code recall_insights} — search distilled insights from Cross-Session LTM. */
    @Component
    @ToolDoc(
            description =
                    "Search the user's distilled insights (Cross-Session LTM) for knowledge "
                            + "that spans multiple sessions.",
            usage =
                    """
                    #### When to use
                    Use `recall_insights` when you need knowledge that persists across sessions - \
                    project conventions, recurring patterns, lessons learned, or architectural \
                    decisions that were previously captured as insights.

                    #### When NOT to use
                    - Do not use `recall_insights` for current-session context - use `recall_session`.
                    - Do not use it when the information is in your active context.
                    - Do not use it to read files - use `view_file`.

                    #### Behavior
                    Embeds `query` and performs cosine similarity search in Cross-Session LTM \
                    (curated insights promoted from Session LTM). Returns top-K matches ranked by \
                    similarity, filtered by `scoreFloor`. Cross-session visibility is user-specific.

                    #### Return format
                    A numbered list of insights, each with id, score, source type, and content.

                    #### Errors & edge cases
                    Unknown query returns empty. If no insights have been written for this user, \
                    returns empty. `scoreFloor` too high returns empty.

                    #### Security
                    Agent tool (`RiskCategory.AGENT`). The Gateway does not screen it. Tenant \
                    isolation enforced - only the owning user's insights are visible. Safe to call \
                    any time.
                    """,
            examples = {
                "{\"query\": \"project configuration patterns\"}",
                "{\"query\": \"authentication\", \"topK\": 3}"
            },
            returnExamples = {
                "1. [0.88] (insight) Always externalize secrets to the keystead vault, never...\n"
                        + "2. [0.71] (insight) Prefer constructor injection over field injection...",
                "(no matches)"
            })
    public static final class RecallInsights implements AgentTool<RecallInsights.Args> {

        private final MemoryStore store;

        public RecallInsights(MemoryStore store) {
            this.store = store;
        }

        public record Args(
                @SecurityHint(ParamCategory.GENERIC) @Doc("Free-text query to embed + search.")
                        String query,
                @Nullable @Doc("Optional top-K; defaults to 5.") Integer topK,
                @Nullable @Doc("Optional score floor in [0,1]; defaults to 0.5.")
                        Float scoreFloor) {}

        @Override
        public String getName() {
            return "recall_insights";
        }

        @Override
        public String getDescription() {
            return "Search the user's distilled insights (Cross-Session LTM) for knowledge "
                    + "that spans multiple sessions.";
        }

        @Override
        public Class<Args> getArgsClass() {
            return Args.class;
        }

        @Override
        public @NonNull String execute(@NonNull Args args) {
            ToolCallContext ctx = ToolCallContextHolder.get();
            if (ctx == null) {
                return "no matching memories"; // no user context available
            }
            String query = args.query() == null ? "" : args.query();
            int topK = args.topK() != null && args.topK() > 0 ? args.topK() : 5;
            float scoreFloor = args.scoreFloor() != null ? clamp01(args.scoreFloor()) : 0.5f;
            MemoryQuery q =
                    new MemoryQuery(
                            query,
                            List.of(MemoryTier.CROSS_SESSION),
                            null,
                            null,
                            ctx.userId(),
                            topK,
                            scoreFloor);
            return formatResults(store.search(q));
        }
    }

    /**
     * {@code write_insight} — write a new insight to Cross-Session LTM, or promote a Session LTM
     * item.
     */
    @Component
    @ToolDoc(
            description =
                    "Write a new insight to Cross-Session LTM, or promote a Session LTM memory "
                            + "to cross-session visibility.",
            usage =
                    """
                    #### When to use
                    Use `write_insight` to persist knowledge that will be useful in future sessions - \
                    project conventions, recurring patterns, architectural decisions, or lessons \
                    learned. Also use it to promote a Session LTM memory to Cross-Session LTM when \
                    its value extends beyond this session.

                    #### When NOT to use
                    - Do not use `write_insight` for transient context that only matters this session - \
                    Session LTM captures automatically.
                    - Do not use it to record verbatim file contents - reference the file path instead.
                    - Do not write trivial or obvious facts; insights should be non-obvious, reusable \
                    knowledge.

                    #### Behavior
                    Two modes: (1) Direct write - stores `content` as a new insight in Cross-Session \
                    LTM, tagged with `projectId` if provided. Content is masked (secrets removed). \
                    (2) Promotion - if `promoteMemoryId` is given, promotes that Session LTM memory \
                    to Cross-Session LTM, expanding its visibility to all sessions.

                    #### Return format
                    `{"status": "ok", "memoryId": "insight-abc123"}`

                    #### Errors & edge cases
                    Empty `content` is rejected. If `promoteMemoryId` does not exist, returns error. \
                    Content is masked before storage - secrets are stripped.

                    #### Security
                    Agent tool (`RiskCategory.AGENT`). The Gateway does not screen it. Self-edit \
                    operation (audited). Content is already masked at capture point. Safe to call \
                    any time.
                    """,
            examples = {
                "{\"content\": \"This project uses Gradle 8.5 with Kotlin DSL. Custom task: compileKotlin\"}",
                "{\"content\": \"Auth middleware in application.yml\", \"projectId\": \"project-xyz\"}",
                "{\"content\": \"...\", \"promoteMemoryId\": \"session-mem-456\"}"
            },
            returnExamples = {
                "{\"status\": \"ok\", \"memoryId\": \"insight-abc123\"}",
                "{\"status\": \"error\", \"error\": \"promoteMemoryId not found: session-mem-456\"}"
            })
    public static final class WriteInsight implements AgentTool<WriteInsight.Args> {

        private final MemoryStore store;
        private final Embedder embedder;

        public WriteInsight(MemoryStore store, Embedder embedder) {
            this.store = store;
            this.embedder = embedder;
        }

        public record Args(
                @SecurityHint(ParamCategory.GENERIC)
                        @Doc("The insight text to remember (already masked).")
                        String content,
                @Nullable @Doc("Optional Session-LTM memory id to promote (curating boundary).")
                        String promoteMemoryId,
                @Nullable @Doc("Optional project id to tag the insight with.") String projectId) {}

        @Override
        public String getName() {
            return "write_insight";
        }

        @Override
        public String getDescription() {
            return "Write a new insight to Cross-Session LTM, or promote a Session LTM memory "
                    + "to cross-session visibility.";
        }

        @Override
        public Class<Args> getArgsClass() {
            return Args.class;
        }

        @Override
        public @NonNull String execute(@NonNull Args args) {
            ToolCallContext ctx = ToolCallContextHolder.get();
            if (ctx == null) {
                return "no user context; insight not written";
            }
            // Promote path: turn a Session-LTM memory into a distilled Cross-Session insight.
            String promoteId = args.promoteMemoryId();
            if (promoteId != null && !promoteId.isBlank()) {
                try {
                    store.promote(new MemoryId(UUID.fromString(promoteId.strip())));
                    return "promoted";
                } catch (IllegalArgumentException e) {
                    return "invalid promoteMemoryId; not promoted";
                }
            }
            String content = args.content() == null ? "" : args.content();
            if (content.isBlank()) {
                return "no content; insight not written";
            }
            UUID projectId = parseUuidOrNull(args.projectId());
            Memory m =
                    new Memory(
                            MemoryId.random(),
                            ctx.userId(),
                            null, // CROSS_SESSION strips the sessionId (the curating boundary)
                            MemoryTier.CROSS_SESSION,
                            projectId,
                            content,
                            embedder.embed(content),
                            Memory.SourceRef.insightOrigin("write_insight"),
                            Instant.now());
            MemoryId id = store.add(m);
            return "insight written: " + id.value();
        }
    }

    /** {@code forget} — explicitly drop a memory. */
    @Component
    @ToolDoc(
            description = "Explicitly drop a memory from the agent's long-term store.",
            usage =
                    """
                    #### When to use
                    Use `forget` when a previously captured memory or insight is wrong, outdated, or \
                    no longer relevant - correcting stale knowledge before it misleads future reasoning.

                    #### When NOT to use
                    - Do not use `forget` to clear session context - that is automatic.
                    - Do not use it speculatively; only forget what you know is wrong.
                    - Do not forget memories you have not verified are incorrect.

                    #### Behavior
                    Permanently deletes the memory identified by `memoryId` from the store. Cannot be \
                    recovered. Audited for compliance.

                    #### Return format
                    `{"status": "ok", "memoryId": "abc123", "forgotten": true}`

                    #### Errors & edge cases
                    Memory not found -> `{"error": "Memory not found"}`. Not owner (cross-user) -> \
                    blocked by tenant isolation.

                    #### Security
                    Agent tool (`RiskCategory.AGENT`). The Gateway does not screen it. Permanent \
                    deletion, audited. Safe to call any time.
                    """,
            examples = {"{\"memoryId\": \"insight-abc123\"}"},
            returnExamples = {"forgotten", "invalid memoryId; nothing forgotten"})
    public static final class Forget implements AgentTool<Forget.Args> {

        private final MemoryStore store;

        public Forget(MemoryStore store) {
            this.store = store;
        }

        public record Args(
                @SecurityHint(ParamCategory.GENERIC) @Doc("The memory id to forget.")
                        String memoryId) {}

        @Override
        public String getName() {
            return "forget";
        }

        @Override
        public String getDescription() {
            return "Explicitly drop a memory from the agent's long-term store.";
        }

        @Override
        public Class<Args> getArgsClass() {
            return Args.class;
        }

        @Override
        public @NonNull String execute(@NonNull Args args) {
            String id = args.memoryId();
            if (id == null || id.isBlank()) {
                return "no memoryId; nothing forgotten";
            }
            try {
                store.forget(new MemoryId(UUID.fromString(id.strip())));
                return "forgotten";
            } catch (IllegalArgumentException e) {
                return "invalid memoryId; nothing forgotten";
            }
        }
    }

    /**
     * Helper to format scored-memory results for a tool observation (DATA — not instructions
     * framing is applied at ingress via {@code IngressDefense}).
     */
    public static @NonNull String formatResults(@NonNull List<MemoryStore.ScoredMemory> results) {
        if (results == null || results.isEmpty()) {
            return "no matching memories";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(results.size()).append(" memories:\n");
        for (MemoryStore.ScoredMemory sm : results) {
            Memory m = sm.memory();
            sb.append("- [")
                    .append(m.tier())
                    .append("] id=")
                    .append(m.id().value())
                    .append(" score=")
                    .append(String.format("%.3f", sm.score()))
                    .append(" src=")
                    .append(m.sourceRef().kind())
                    .append(" ")
                    .append(m.sourceRef().attrs())
                    .append("\n");
            String content = m.content();
            if (content != null && content.length() > 240) {
                content = content.substring(0, 240) + "...";
            }
            sb.append("  ").append(content).append("\n");
        }
        return sb.toString();
    }

    /** Helper to extract the userId from a context map (the agent's per-session user). */
    public static @NonNull UUID userIdFromContext(@NonNull Map<String, Object> context) {
        if (context == null) {
            return null;
        }
        Object v = context.get("userId");
        return v instanceof UUID u ? u : null;
    }

    /** Clamps a score-floor value into [0, 1] (the {@link MemoryQuery} validity range). */
    static float clamp01(float v) {
        if (v < 0f) {
            return 0f;
        }
        if (v > 1f) {
            return 1f;
        }
        return v;
    }

    /** Parses a UUID string, returning null on blank/invalid input (for optional id args). */
    static @Nullable UUID parseUuidOrNull(@Nullable String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(s.strip());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
