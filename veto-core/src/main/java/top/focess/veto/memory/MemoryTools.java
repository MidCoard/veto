package top.focess.veto.memory;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.mcp.AgentTool;
import top.focess.veto.agent.mcp.Doc;
import top.focess.veto.agent.mcp.ParamCategory;
import top.focess.veto.agent.mcp.SecurityHint;
import top.focess.veto.agent.mcp.ToolCallContext;
import top.focess.veto.agent.mcp.ToolCallContextHolder;
import top.focess.veto.agent.mcp.ToolCapability;
import top.focess.veto.agent.mcp.ToolDoc;
import top.focess.veto.agent.mcp.ToolDocs;
import top.focess.veto.agent.mcp.ToolErrors;
import top.focess.veto.agent.mcp.ToolResultFormat;
import top.focess.veto.memory.embedder.Embedder;

/**
 * The agent-facing memory tools (long_term_memory_tiers.md §6). These are agent tools — they carry
 * {@link top.focess.veto.agent.mcp.RiskCategory#AGENT}; the Gateway returns {@code NotScreened}.
 * They still flow through the LoopInterceptor chain for audit.
 */
@SuppressWarnings("DuplicatedCode") // Each native memory tool applies the same context guard.
public final class MemoryTools {

    private static final int MAX_QUERY_CHARS = 4000;
    private static final int MAX_TOP_K = 20;
    private static final int MAX_INSIGHT_CHARS = 64_000;

    private MemoryTools() {}

    /** {@code recall_session} — search captured chunks from the current Session LTM. */
    @Component
    @ToolDoc(
            resultFormats = {ToolResultFormat.PLAINTEXT},
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
                    Plain text beginning `<count> memories:`, followed by bullet entries containing \
                    tier, id, score, source, and a content snippet. No match returns \
                    `no matching memories`.

                    #### Errors & edge cases
                    If `scoreFloor` is too high, the query is empty, or the session has no matching \
                    memories, returns exactly `no matching memories` rather than inventing matches.

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
                "1 memories:\n- [SESSION] id=123e4567-e89b-12d3-a456-426614174000 score=0.820 src=TOOL_RESULT {}\n"
                        + "  UserService.authenticate validates the JWT expiry and...",
                "no matching memories"
            })
    public static final class RecallSession implements AgentTool<RecallSession.Args> {

        private final @NonNull MemoryStore store;

        public RecallSession(@NonNull MemoryStore store) {
            this.store = store;
        }

        public record Args(
                @SecurityHint(ParamCategory.GENERIC) @Doc("Free-text query to embed + search.")
                        @NonNull String query,
                @Doc("Optional top-K; defaults to 5.") Integer topK,
                @Doc("Optional score floor in [0,1]; defaults to 0.5.") Float scoreFloor) {}

        @Override
        public @NonNull String getName() {
            return "recall_session";
        }

        @Override
        public @NonNull String getDescription() {
            return "Search the current session's captured long-term memory (Session LTM) "
                    + "for relevant context.";
        }

        @Override
        public @NonNull Class<Args> getArgsClass() {
            return ToolDocs.nonNullClass(Args.class);
        }

        @Override
        public @NonNull ToolCapability getCapability() {
            return ToolCapability.MEMORY_READ;
        }

        @Override
        public @NonNull String execute(@NonNull Args args) {
            ToolCallContext ctx = ToolCallContextHolder.get();
            if (ctx == null) {
                return ToolErrors.failure("no session context; memories not recalled");
            }
            String query = boundedQuery(args.query());
            int topK = boundedTopK(args.topK());
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
            resultFormats = {ToolResultFormat.PLAINTEXT},
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
                    Plain text beginning `<count> memories:`, followed by bullet entries containing \
                    tier, id, score, source, and content. No match returns `no matching memories`.

                    #### Errors & edge cases
                    An unknown query, an empty insight store, or a `scoreFloor` that is too high \
                    returns exactly `no matching memories`.

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
                "1 memories:\n- [CROSS_SESSION] id=123e4567-e89b-12d3-a456-426614174000 score=0.880 src=INSIGHT {}\n"
                        + "  Prefer constructor injection over field injection...",
                "no matching memories"
            })
    public static final class RecallInsights implements AgentTool<RecallInsights.Args> {

        private final @NonNull MemoryStore store;

        public RecallInsights(@NonNull MemoryStore store) {
            this.store = store;
        }

        public record Args(
                @SecurityHint(ParamCategory.GENERIC) @Doc("Free-text query to embed + search.")
                        @NonNull String query,
                @Doc("Optional top-K; defaults to 5.") Integer topK,
                @Doc("Optional score floor in [0,1]; defaults to 0.5.") Float scoreFloor) {}

        @Override
        public @NonNull String getName() {
            return "recall_insights";
        }

        @Override
        public @NonNull String getDescription() {
            return "Search the user's distilled insights (Cross-Session LTM) for knowledge "
                    + "that spans multiple sessions.";
        }

        @Override
        public @NonNull Class<Args> getArgsClass() {
            return ToolDocs.nonNullClass(Args.class);
        }

        @Override
        public @NonNull ToolCapability getCapability() {
            return ToolCapability.MEMORY_READ;
        }

        @Override
        public @NonNull String execute(@NonNull Args args) {
            ToolCallContext ctx = ToolCallContextHolder.get();
            if (ctx == null) {
                return ToolErrors.failure("no user context; insights not recalled");
            }
            String query = boundedQuery(args.query());
            int topK = boundedTopK(args.topK());
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
            resultFormats = {ToolResultFormat.PLAINTEXT},
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
                    Set `mode` to `WRITE` to store `content` as a new Cross-Session insight, tagged \
                    with a UUID `projectId` when provided. Set `mode` to `PROMOTE` and provide only \
                    `promoteMemoryId` to promote an existing Session-LTM memory. The two modes are \
                    mutually exclusive; fields from the other mode are rejected.

                    #### Return format
                    - Direct-write success: \
                    `insight written: <memory UUID>`.
                    - Promotion success: `promoted`.
                    - Promotion failure: \
                    `memory not found or not owned; not promoted`.
                    - Write failure: \
                    `no content; insight not written` or \
                    `insight exceeds 64000 characters; not written` or \
                    `invalid projectId; insight not written`.
                    - Mode-field mismatch: `PROMOTE accepts only promoteMemoryId; insight not \
                    promoted` or `WRITE does not accept promoteMemoryId; insight not written`.

                    #### Errors & edge cases
                    Empty `content` is rejected in `WRITE` mode. An invalid or inaccessible \
                    `promoteMemoryId` has the same failure. An invalid `projectId` is rejected. \
                    Do not put secrets or verbatim file contents in an insight.

                    #### Security
                    Agent tool (`RiskCategory.AGENT`). The Gateway does not screen it. Self-edit \
                    operation (audited). The supplied content is stored as given; this tool does not \
                    perform Gateway redaction. Never supply secrets. Safe to call any time.
                    """,
            examples = {
                "{\"mode\": \"WRITE\", \"content\": \"This project uses Gradle 8.5 with Kotlin DSL\"}",
                "{\"mode\": \"PROMOTE\", \"promoteMemoryId\": \"123e4567-e89b-12d3-a456-426614174000\"}",
                "{\"mode\": \"WRITE\", \"content\": \"Prefer constructor injection\", \"projectId\": \"123e4567-e89b-12d3-a456-426614174000\"}"
            },
            returnExamples = {"insight written: 123e4567-e89b-12d3-a456-426614174000", "promoted"})
    public static final class WriteInsight implements AgentTool<WriteInsight.Args> {

        private final @NonNull MemoryStore store;
        private final @NonNull Embedder embedder;

        public WriteInsight(@NonNull MemoryStore store, @NonNull Embedder embedder) {
            this.store = store;
            this.embedder = embedder;
        }

        public enum Mode {
            WRITE,
            PROMOTE
        }

        public record Args(
                @Doc("Required operation: WRITE a new insight or PROMOTE an existing memory.")
                        @NonNull Mode mode,
                @SecurityHint(ParamCategory.GENERIC)
                        @Doc("Insight text; required only in WRITE mode. Never include secrets.")
                        String content,
                @Doc("Session-LTM memory UUID; required only in PROMOTE mode.")
                        String promoteMemoryId,
                @Doc("Optional project UUID for WRITE mode.") String projectId) {}

        @Override
        public @NonNull String getName() {
            return "write_insight";
        }

        @Override
        public @NonNull String getDescription() {
            return "Write a new insight to Cross-Session LTM, or promote a Session LTM memory "
                    + "to cross-session visibility.";
        }

        @Override
        public @NonNull Class<Args> getArgsClass() {
            return ToolDocs.nonNullClass(Args.class);
        }

        @Override
        public @NonNull ToolCapability getCapability() {
            return ToolCapability.MEMORY_WRITE;
        }

        @Override
        public @NonNull String execute(@NonNull Args args) {
            ToolCallContext ctx = ToolCallContextHolder.get();
            if (ctx == null) {
                return ToolErrors.failure("no user context; insight not written");
            }
            if (args.mode() == Mode.PROMOTE) {
                if ((args.content() != null && !args.content().isBlank())
                        || (args.projectId() != null && !args.projectId().isBlank())) {
                    return ToolErrors.failure(
                            "PROMOTE accepts only promoteMemoryId; insight not promoted");
                }
                String promoteId = args.promoteMemoryId();
                if (promoteId == null || promoteId.isBlank()) {
                    return ToolErrors.failure("memory not found or not owned; not promoted");
                }
                try {
                    boolean promoted =
                            store.promote(
                                    new MemoryId(UUID.fromString(promoteId.strip())), ctx.userId());
                    return promoted
                            ? "promoted"
                            : ToolErrors.failure("memory not found or not owned; not promoted");
                } catch (IllegalArgumentException e) {
                    return ToolErrors.failure("memory not found or not owned; not promoted");
                }
            }
            if (args.promoteMemoryId() != null && !args.promoteMemoryId().isBlank()) {
                return ToolErrors.failure(
                        "WRITE does not accept promoteMemoryId; insight not written");
            }
            String content = args.content() == null ? "" : args.content();
            if (content.isBlank()) {
                return ToolErrors.failure("no content; insight not written");
            }
            if (content.length() > MAX_INSIGHT_CHARS) {
                return ToolErrors.failure("insight exceeds 64000 characters; not written");
            }
            UUID projectId = parseUuidOrNull(args.projectId());
            if (args.projectId() != null && !args.projectId().isBlank() && projectId == null) {
                return ToolErrors.failure("invalid projectId; insight not written");
            }
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
            resultFormats = {ToolResultFormat.PLAINTEXT},
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
                    - Success -> `forgotten`.
                    - Invalid, missing, or cross-user id -> failed result: \
                    `memory not found or not owned; nothing forgotten`.
                    - Missing `memoryId` is rejected before execution; the diagnostic identifies \
                    the missing required parameter.

                    #### Errors & edge cases
                    Use a memory id returned by a recall tool. Missing and cross-user ids intentionally \
                    share one diagnostic so tenant isolation does not reveal whether another user's \
                    memory exists. No failure deletes anything.

                    #### Security
                    Agent tool (`RiskCategory.AGENT`). The Gateway does not screen it. Permanent \
                    deletion, audited. Safe to call any time.
                    """,
            examples = {"{\"memoryId\": \"123e4567-e89b-12d3-a456-426614174000\"}"},
            returnExamples = {"forgotten"})
    public static final class Forget implements AgentTool<Forget.Args> {

        private final @NonNull MemoryStore store;

        public Forget(@NonNull MemoryStore store) {
            this.store = store;
        }

        public record Args(
                @SecurityHint(ParamCategory.GENERIC) @Doc("The memory id to forget.")
                        @NonNull String memoryId) {}

        @Override
        public @NonNull String getName() {
            return "forget";
        }

        @Override
        public @NonNull String getDescription() {
            return "Explicitly drop a memory from the agent's long-term store.";
        }

        @Override
        public @NonNull Class<Args> getArgsClass() {
            return ToolDocs.nonNullClass(Args.class);
        }

        @Override
        public @NonNull ToolCapability getCapability() {
            return ToolCapability.MEMORY_WRITE;
        }

        @Override
        public @NonNull String execute(@NonNull Args args) {
            ToolCallContext ctx = ToolCallContextHolder.get();
            if (ctx == null) {
                return ToolErrors.failure("no user context; nothing forgotten");
            }
            String id = args.memoryId();
            if (id == null || id.isBlank()) {
                return ToolErrors.failure("memory not found or not owned; nothing forgotten");
            }
            try {
                boolean forgotten =
                        store.forget(new MemoryId(UUID.fromString(id.strip())), ctx.userId());
                return forgotten
                        ? "forgotten"
                        : ToolErrors.failure("memory not found or not owned; nothing forgotten");
            } catch (IllegalArgumentException e) {
                return ToolErrors.failure("memory not found or not owned; nothing forgotten");
            }
        }
    }

    /**
     * Helper to format scored-memory results for a tool observation (DATA — not instructions
     * framing is applied at ingress via {@code IngressDefense}).
     */
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
                    .append(String.format("%.3f", sm.score()))
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

    /** Helper to extract the userId from a context map (the agent's per-session user). */
    public static UUID userIdFromContext(@NonNull Map<String, Object> context) {
        Object v = context.get("userId");
        return v instanceof UUID u ? u : null;
    }

    /** Clamps a score-floor value into [0, 1] (the {@link MemoryQuery} validity range). */
    static float clamp01(float v) {
        return Math.clamp(v, 0f, 1f);
    }

    private static int boundedTopK(Integer requested) {
        int value = requested != null && requested > 0 ? requested : 5;
        return Math.min(value, MAX_TOP_K);
    }

    private static @NonNull String boundedQuery(String query) {
        if (query == null) {
            return "";
        }
        return query.length() <= MAX_QUERY_CHARS ? query : query.substring(0, MAX_QUERY_CHARS);
    }

    /** Parses a UUID string, returning null on blank/invalid input (for optional id args). */
    static UUID parseUuidOrNull(String s) {
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
