package top.focess.veto.memory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.tool.AgentTool;
import top.focess.veto.agent.tool.Doc;
import top.focess.veto.agent.tool.ParamCategory;
import top.focess.veto.agent.tool.RequiredWhen;
import top.focess.veto.agent.tool.SecurityHint;
import top.focess.veto.agent.tool.ToolCallContext;
import top.focess.veto.agent.tool.ToolCallContextHolder;
import top.focess.veto.agent.tool.ToolCapability;
import top.focess.veto.agent.tool.ToolDoc;
import top.focess.veto.agent.tool.ToolDocs;
import top.focess.veto.agent.tool.ToolErrors;
import top.focess.veto.agent.tool.ToolResultFormat;
import top.focess.veto.memory.embedder.Embedder;
import top.focess.veto.util.Nullness;

/**
 * Agent-facing memory tools. Their agent-tool definition flavour means the Gateway returns {@code
 * NotScreened}. They still flow through the LoopInterceptor chain for audit.
 */
public final class MemoryTools {

    private static final int MAX_QUERY_CHARS = 4000;
    private static final int RECALL_RESULT_LIMIT = 5;
    private static final float RECALL_SCORE_FLOOR = 0.5f;
    private static final int MAX_MEMORY_CHARS = 64_000;

    private MemoryTools() {}

    /** {@code recall_memory} — search current-session memories and cross-session insights. */
    @Component
    @ToolDoc(
            resultFormats = {ToolResultFormat.PLAINTEXT},
            description =
                    "Search the current session's captured memory and the user's cross-session "
                            + "insights together.",
            behavior =
                    """
                    Embeds `query`, searches both the current session and the user's cross-session \
                    insights, combines the matches, and returns the 5 highest-scoring results above \
                    the 0.5 similarity threshold. `query` is capped at 4000 characters. Every result \
                    identifies its tier and source and includes a content snippet of at most 240 \
                    characters.
                    """,
            whenToUse =
                    """
                    Use `recall_memory` when relevant information is no longer in the active context: \
                    earlier decisions or observations from this session, or reusable knowledge saved \
                    from previous sessions.
                    """,
            whenNotToUse =
                    """
                    - Do not use it when the information is still in your active context - just \
                    reference it directly.
                    - Do not use it as a substitute for `view_file` or `grep_search` for finding code.
                    """,
            resultContract =
                    """
                    Plain text beginning `<count> memories:`, followed by bullet entries containing \
                    tier, id, score, source, and a content snippet. No match returns \
                    `no matching memories`. Missing session context fails with \
                    `no session context; memories not recalled`.
                    """,
            errorsAndEdgeCases =
                    """
                    An unknown query or empty memory stores can legitimately yield zero matches. \
                    Refine the query before retrying; never invent absent memories.
                    """,
            security =
                    "Session results belong to the current session; all results belong to the current user.",
            examples = {
                "{\"query\": \"UserService authentication\"}",
                "{\"query\": \"build configuration\"}"
            },
            returnExamples = {
                "2 memories:\n- [CROSS_SESSION] id=123e4567-e89b-12d3-a456-426614174000 score=0.880 src=INSIGHT {}\n"
                        + "  Prefer constructor injection over field injection...\n"
                        + "- [SESSION] id=123e4567-e89b-12d3-a456-426614174001 score=0.820 src=turn_range {from=12, to=12}\n"
                        + "  UserService.authenticate validates the JWT expiry and...",
                "no matching memories"
            })
    public static final class RecallMemory implements AgentTool<RecallMemory.Args> {

        private final @NonNull MemoryStore store;

        public RecallMemory(@NonNull MemoryStore store) {
            this.store = store;
        }

        public record Args(
                @SecurityHint(ParamCategory.GENERIC) @Doc("Free-text query to embed + search.")
                        @NonNull String query) {}

        @Override
        public @NonNull String getName() {
            return "recall_memory";
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
            ToolCallContext ctx = contextOrFailure("no session context; memories not recalled");
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
    }

    /** {@code write_memory} — write durable memory or promote a Session-LTM item. */
    @Component
    @ToolDoc(
            resultFormats = {ToolResultFormat.PLAINTEXT},
            description =
                    "Write durable cross-session memory, or promote a Session-LTM memory to "
                            + "cross-session visibility.",
            behavior =
                    """
                    Set `mode` to `WRITE` to store `content` as new durable Cross-Session memory, tagged \
                    with a UUID `projectId` when provided. Set `mode` to `PROMOTE` and provide only \
                    `promoteMemoryId` to replace an existing Session-LTM memory with a new \
                    Cross-Session memory. Non-blank fields from the other mode are rejected. A \
                    successful promotion invalidates the old id and returns the replacement id.
                    """,
            whenToUse =
                    """
                    Use `write_memory` to persist knowledge that will be useful in future sessions - \
                    project conventions, recurring patterns, architectural decisions, or lessons \
                    learned. Also use it to promote a Session LTM memory to Cross-Session LTM when \
                    its value extends beyond this session.
                    """,
            whenNotToUse =
                    """
                    - Do not use `write_memory` for transient context that only matters this session - \
                    Session LTM captures automatically.
                    - Do not use it to record verbatim file contents - reference the file path instead.
                    - Do not write trivial or obvious facts; insights should be non-obvious, reusable \
                    knowledge.
                    """,
            resultContract =
                    """
                    - Direct-write success: \
                    `memory written: <memory UUID>`.
                    - Promotion success: `promoted: <new memory UUID>`.
                    - Promotion failure: \
                    `memory not found or not owned; not promoted`.
                    - Write failure: \
                    `no content; memory not written` or \
                    `memory exceeds 64000 characters; not written` or \
                    `invalid projectId; memory not written` (the value is not a UUID).
                    - Mode-field mismatch: `PROMOTE accepts only promoteMemoryId; memory not \
                    promoted` or `WRITE does not accept promoteMemoryId; memory not written`.
                    """,
            errorsAndEdgeCases =
                    """
                    `WRITE` accepts content plus an optional project id; `PROMOTE` accepts only a memory id. \
                    Correct a mode/field mismatch before retrying. Ownership and absence deliberately share a \
                    promotion failure so tenant isolation leaks nothing. Never store secrets or verbatim file \
                    contents in durable memory.
                    """,
            security = "Content is stored as supplied. Never include secrets.",
            examples = {
                "{\"mode\": \"WRITE\", \"content\": \"This project uses Gradle 8.5 with Kotlin DSL\"}",
                "{\"mode\": \"PROMOTE\", \"promoteMemoryId\": \"123e4567-e89b-12d3-a456-426614174000\"}",
                "{\"mode\": \"WRITE\", \"content\": \"Prefer constructor injection\", \"projectId\": \"123e4567-e89b-12d3-a456-426614174000\"}"
            },
            returnExamples = {
                "memory written: 123e4567-e89b-12d3-a456-426614174000",
                "promoted: 123e4567-e89b-12d3-a456-426614174000"
            })
    public static final class WriteMemory implements AgentTool<WriteMemory.Args> {

        private final @NonNull MemoryStore store;
        private final @NonNull Embedder embedder;

        public WriteMemory(@NonNull MemoryStore store, @NonNull Embedder embedder) {
            this.store = store;
            this.embedder = embedder;
        }

        public enum Mode {
            WRITE,
            PROMOTE
        }

        public record Args(
                @Doc("Required operation: WRITE new durable memory or PROMOTE existing memory.")
                        @NonNull Mode mode,
                @SecurityHint(ParamCategory.GENERIC)
                        @Doc(
                                "Durable memory text; required only in WRITE mode. Never include secrets.")
                        @RequiredWhen(field = "mode", values = "WRITE", rejectBlank = true)
                        String content,
                @Doc("Session-LTM memory UUID; required only in PROMOTE mode.")
                        @RequiredWhen(field = "mode", values = "PROMOTE", rejectBlank = true)
                        String promoteMemoryId,
                @Doc("Optional project UUID for WRITE mode.") String projectId) {}

        @Override
        public @NonNull String getName() {
            return "write_memory";
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
            ToolCallContext ctx = contextOrFailure("no user context; memory not written");
            if (args.mode() == Mode.PROMOTE) {
                if ((args.content() != null && !args.content().isBlank())
                        || (args.projectId() != null && !args.projectId().isBlank())) {
                    return ToolErrors.failure(
                            "PROMOTE accepts only promoteMemoryId; memory not promoted");
                }
                String promoteId =
                        Nullness.requireNonNull(
                                args.promoteMemoryId(),
                                "RequiredWhen validation must supply promoteMemoryId");
                try {
                    MemoryId promoted =
                            store.promote(
                                    new MemoryId(UUID.fromString(promoteId.strip())), ctx.userId());
                    return promoted != null
                            ? "promoted: " + promoted.value()
                            : ToolErrors.failure("memory not found or not owned; not promoted");
                } catch (IllegalArgumentException e) {
                    return ToolErrors.failure("memory not found or not owned; not promoted");
                }
            }
            if (args.promoteMemoryId() != null && !args.promoteMemoryId().isBlank()) {
                return ToolErrors.failure(
                        "WRITE does not accept promoteMemoryId; memory not written");
            }
            String content =
                    Nullness.requireNonNull(
                            args.content(), "RequiredWhen validation must supply content");
            if (content.length() > MAX_MEMORY_CHARS) {
                return ToolErrors.failure("memory exceeds 64000 characters; not written");
            }
            UUID projectId = parseUuidOrNull(args.projectId());
            if (args.projectId() != null && !args.projectId().isBlank() && projectId == null) {
                return ToolErrors.failure("invalid projectId; memory not written");
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
                            Memory.SourceRef.insightOrigin("write_memory"),
                            Instant.now());
            MemoryId id = store.add(m);
            return "memory written: " + id.value();
        }
    }

    /** {@code forget_memory} — explicitly drop a memory. */
    @Component
    @ToolDoc(
            resultFormats = {ToolResultFormat.PLAINTEXT},
            description = "Explicitly drop a memory from the agent's long-term store.",
            behavior =
                    """
                    Permanently deletes the memory identified by `memoryId` from the store. It cannot \
                    be recovered through this tool.
                    """,
            whenToUse =
                    """
                    Use `forget_memory` when a previously captured memory or insight is wrong, outdated, or \
                    no longer relevant - correcting stale knowledge before it misleads future reasoning.
                    """,
            whenNotToUse =
                    """
                    - Do not use `forget_memory` to clear session context - that is automatic.
                    - Do not use it speculatively; only forget what you know is wrong.
                    - Do not forget memories you have not verified are incorrect.
                    """,
            resultContract =
                    """
                    - Success -> `forgotten: <memoryId>`.
                    - Invalid, unknown, or cross-user id -> failed result: \
                    `memory not found or not owned; nothing forgotten`.
                    """,
            errorsAndEdgeCases =
                    """
                    Use an id returned by `recall_memory` or `write_memory`. Ownership and absence deliberately \
                    share the contract's failure body so tenant isolation reveals nothing.
                    """,
            security =
                    "Deletion is permanent. Remove only a memory you have verified should be removed.",
            examples = {"{\"memoryId\": \"123e4567-e89b-12d3-a456-426614174000\"}"},
            returnExamples = {"forgotten: 123e4567-e89b-12d3-a456-426614174000"})
    public static final class ForgetMemory implements AgentTool<ForgetMemory.Args> {

        private final @NonNull MemoryStore store;

        public ForgetMemory(@NonNull MemoryStore store) {
            this.store = store;
        }

        public record Args(
                @SecurityHint(ParamCategory.GENERIC) @Doc("The memory id to forget.")
                        @NonNull String memoryId) {}

        @Override
        public @NonNull String getName() {
            return "forget_memory";
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
            ToolCallContext ctx = contextOrFailure("no user context; nothing forgotten");
            String id = args.memoryId();
            if (id.isBlank()) {
                return ToolErrors.failure("memory not found or not owned; nothing forgotten");
            }
            try {
                MemoryId memoryId = new MemoryId(UUID.fromString(id.strip()));
                boolean forgotten = store.forget(memoryId, ctx.userId());
                return forgotten
                        ? "forgotten: " + memoryId.value()
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

    /** Helper to extract the userId from a context map (the agent's per-session user). */
    public static UUID userIdFromContext(@NonNull Map<String, Object> context) {
        Object v = context.get("userId");
        return v instanceof UUID u ? u : null;
    }

    private static @NonNull String boundedQuery(@NonNull String query) {
        return query.length() <= MAX_QUERY_CHARS ? query : query.substring(0, MAX_QUERY_CHARS);
    }

    private static @NonNull ToolCallContext contextOrFailure(@NonNull String message) {
        ToolCallContext context = ToolCallContextHolder.get();
        return context == null ? ToolErrors.failure(message) : context;
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
