package top.focess.veto.memory;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.mcp.Doc;
import top.focess.veto.agent.mcp.NativeMcpTool;
import top.focess.veto.agent.mcp.ParamCategory;
import top.focess.veto.agent.mcp.RiskCategory;
import top.focess.veto.agent.mcp.SecurityHint;
import top.focess.veto.agent.mcp.ToolCallContext;
import top.focess.veto.agent.mcp.ToolCallContextHolder;
import top.focess.veto.agent.mcp.ToolDoc;
import top.focess.veto.agent.mcp.ToolSecurity;
import top.focess.veto.memory.embedder.Embedder;

/**
 * The agent-facing memory tools (long_term_memory_tiers.md §6). These are native tools, exactly
 * like {@code view_file} or {@code run_command}, so they pass through the Gateway (read tools are
 * {@code SAFE}; write tools are {@code ELEVATED} and audited). They are not host-path tools so they
 * carry no path-class danger; the screening model treats them per the generic/tool-declared option
 * set.
 */
public final class MemoryTools {

    private MemoryTools() {}

    /** {@code recall_session} — search captured chunks from the current Session LTM. */
    @Component
    @ToolSecurity(risk = RiskCategory.READ_ONLY)
    public static final class RecallSession implements NativeMcpTool<RecallSession.Args> {

        private final MemoryStore store;

        public RecallSession(MemoryStore store) {
            this.store = store;
        }

        @ToolDoc(
                description =
                        """
                        #### When to use
                        Use `recall_session` to search the current session's captured long-term memory (Session \
                        LTM) - the running log of facts, decisions, and intermediate results captured during \
                        THIS session. Reach for it when you need to recover something established earlier in the \
                        session that has scrolled out of your immediate context: a prior decision, a file you \
                        already inspected, a fix you already applied, or a constraint the user stated.

                        #### When NOT to use
                        - Do not use `recall_session` for cross-session/distilled knowledge - use \
                        `recall_insights` instead.
                        - Do not use it to read the live filesystem - use `view_file`/`grep_search`.
                        - Do not use it to record a new memory - use `write_insight`.
                        - Do not call it with an empty/vague query; a precise query ranks better.

                        #### Behavior
                        Embeds the free-text `query` and searches the Session LTM store for the current \
                        session/user by vector similarity. Returns up to `topK` memories (default 5) whose \
                        similarity score meets `scoreFloor` (default 0.5), ranked descending. Each result \
                        carries source attribution (where the memory was captured from).

                        #### Return format
                        A formatted list: `<count> memories:` header followed by one entry per memory: \
                        `- [tier] id=<id> score=<score> src=<sourceKind> <attrs>` and the (truncated) content \
                        on the next line. `no matching memories` when nothing meets the floor.

                        #### Errors & edge cases
                        - `topK` omitted -> defaults to 5. `scoreFloor` omitted -> defaults to 0.5.
                        - A floor too high returns fewer/no results; lower it to broaden.
                        - Content longer than 240 chars is truncated in the listing (`...` suffix) - the full \
                        memory is stored, only the preview is cut.
                        - An empty Session LTM (nothing captured yet this session) returns "no matching \
                        memories".

                        #### Security
                        `recall_session` is read-only (`RiskCategory.READ_ONLY`). Its parameters are GENERIC \
                        (no path/content danger). It reads only the current session/user's memories - no \
                        cross-tenant access. Returned memory content is subject to ingress masking. Safe to \
                        call freely.
                        """,
                examples = {
                    "{\"query\": \"how did we fix the auth bug\", \"topK\": 5}",
                    "{\"query\": \"deployment steps\"}",
                    "{\"query\": \"what files did we inspect\", \"topK\": 3}",
                    "{\"query\": \"user constraints\", \"scoreFloor\": 0.4}",
                    "{\"query\": \"the earlier refactor decision\", \"topK\": 10}",
                    "{\"query\": \"config change\"}",
                    "{\"query\": \"test failures\", \"topK\": 8, \"scoreFloor\": 0.6}"
                })
        public record Args(
                @SecurityHint(ParamCategory.GENERIC) @Doc("Free-text query to embed + search.")
                        String query,
                @SecurityHint(ParamCategory.GENERIC) @Doc("Optional top-K; defaults to 5.")
                        Integer topK,
                @SecurityHint(ParamCategory.GENERIC)
                        @Doc("Optional score floor in [0,1]; defaults to 0.5.")
                        Float scoreFloor) {}

        @Override
        public String getName() {
            return "recall_session";
        }

        @Override
        public String getDescription() {
            return "Search the current session's captured long-term memory (Session LTM). Returns up to top-K memories ranked by similarity, with source attribution.";
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
    @ToolSecurity(risk = RiskCategory.READ_ONLY)
    public static final class RecallInsights implements NativeMcpTool<RecallInsights.Args> {

        private final MemoryStore store;

        public RecallInsights(MemoryStore store) {
            this.store = store;
        }

        @ToolDoc(
                description =
                        """
                        #### When to use
                        Use `recall_insights` to search the user's distilled insights (Cross-Session LTM) - \
                        durable, cross-session lessons the user has promoted or that were distilled from prior \
                        sessions: "deploy via gradle bootRun", "the auth module uses JWT", "tests run under H2". \
                        Reach for it at the start of a task to recover knowledge that is NOT in the current \
                        session's context but was learned before.

                        #### When NOT to use
                        - Do not use `recall_insights` for things captured in THIS session only - use \
                        `recall_session`.
                        - Do not use it to read the live filesystem - use `view_file`/`grep_search`.
                        - Do not use it to create an insight - use `write_insight`.
                        - Do not use it to drop a memory - use `forget`.

                        #### Behavior
                        Embeds the free-text `query` and searches the Cross-Session LTM (insights) store for the \
                        current user by vector similarity. Returns up to `topK` memories (default 5) whose \
                        similarity score meets `scoreFloor` (default 0.5), ranked descending. Each result \
                        carries source attribution.

                        #### Return format
                        A formatted list: `<count> memories:` header followed by one entry per memory: \
                        `- [tier] id=<id> score=<score> src=<sourceKind> <attrs>` and the (truncated) content \
                        on the next line. `no matching memories` when nothing meets the floor.

                        #### Errors & edge cases
                        - `topK` omitted -> defaults to 5. `scoreFloor` omitted -> defaults to 0.5.
                        - A floor too high returns fewer/no results; lower it to broaden.
                        - Content longer than 240 chars is truncated in the listing (`...` suffix).
                        - A user with no distilled insights returns "no matching memories".

                        #### Security
                        `recall_insights` is read-only (`RiskCategory.READ_ONLY`). Its parameters are GENERIC. \
                        It reads only the current user's insights - no cross-tenant access. Returned content is \
                        subject to ingress masking. Safe to call freely.
                        """,
                examples = {
                    "{\"query\": \"deployment steps\", \"topK\": 3}",
                    "{\"query\": \"how does auth work\"}",
                    "{\"query\": \"build commands\", \"topK\": 5}",
                    "{\"query\": \"known gotchas\", \"scoreFloor\": 0.4}",
                    "{\"query\": \"test setup\", \"topK\": 10}",
                    "{\"query\": \"database config\"}",
                    "{\"query\": \"prior incident\", \"topK\": 8, \"scoreFloor\": 0.6}"
                })
        public record Args(
                @SecurityHint(ParamCategory.GENERIC) @Doc("Free-text query to embed + search.")
                        String query,
                @SecurityHint(ParamCategory.GENERIC) @Doc("Optional top-K; defaults to 5.")
                        Integer topK,
                @SecurityHint(ParamCategory.GENERIC)
                        @Doc("Optional score floor in [0,1]; defaults to 0.5.")
                        Float scoreFloor) {}

        @Override
        public String getName() {
            return "recall_insights";
        }

        @Override
        public String getDescription() {
            return "Search the user's distilled insights (Cross-Session LTM). Returns up to top-K memories ranked by similarity.";
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
    @ToolSecurity(risk = RiskCategory.FILE_WRITE)
    public static final class WriteInsight implements NativeMcpTool<WriteInsight.Args> {

        private final MemoryStore store;
        private final Embedder embedder;

        public WriteInsight(MemoryStore store, Embedder embedder) {
            this.store = store;
            this.embedder = embedder;
        }

        @ToolDoc(
                description =
                        """
                        #### When to use
                        Use `write_insight` to record a durable, cross-session lesson into Cross-Session LTM - \
                        something worth remembering beyond this session: a deployment step, a project \
                        convention, a gotcha, or a confirmed fact about the codebase. Also use it to promote a \
                        Session-LTM memory (a captured chunk) into a distilled insight via `promoteMemoryId`.

                        Write insights that are generalizable and reusable, not transient session state.

                        #### When NOT to use
                        - Do not use `write_insight` for transient session facts - leave those in Session LTM \
                        (they are captured automatically).
                        - Do not use it to recall - use `recall_insights`/`recall_session`.
                        - Do not use it to drop a memory - use `forget`.
                        - Do not write low-value or duplicative insights; curate.

                        #### Behavior
                        Writes `content` as a new insight in the Cross-Session LTM for the current user. When \
                        `promoteMemoryId` is given, the insight is created by promoting that Session-LTM memory \
                        across the curating boundary (rather than from raw text). When `projectId` is given, the \
                        insight is tagged with that project so it can be scoped on recall. The content is stored \
                        verbatim (it should already be masked of secrets at ingress).

                        #### Return format
                        An acknowledgement / the new insight's id (tool-body dependent). The write is audited.

                        #### Errors & edge cases
                        - `promoteMemoryId` pointing at a non-existent Session-LTM memory -> promotion fails; \
                        write the insight as raw text instead (omit `promoteMemoryId`).
                        - `content` should be concise and self-contained; long rambling insights recall poorly.
                        - `projectId` is optional; omit it for a global insight.
                        - This is a self-edit operation; it is audited and elevated.

                        #### Security
                        `write_insight` is `RiskCategory.FILE_WRITE` (elevated + audited) - it mutates durable \
                        user memory. `content` is CODE_CONTENT-class (semantically screened) so \
                        secrets/instructions cannot be smuggled into long-term memory. Write only the current \
                        user's store - no cross-tenant writes. Mask secrets before writing; do not store \
                        credentials.
                        """,
                examples = {
                    "{\"content\": \"Deploy via gradle bootRun\", \"projectId\": \"veto\"}",
                    "{\"content\": \"Auth uses JWT with 15min expiry\"}",
                    "{\"content\": \"Tests run under H2 in-memory; prod is Postgres\", \"projectId\": \"veto\"}",
                    "{\"content\": \"Never commit application.yml with real secrets\"}",
                    "{\"promoteMemoryId\": \"mem-42\", \"projectId\": \"veto\"}",
                    "{\"content\": \"The build requires JDK 25\", \"projectId\": \"veto\"}",
                    "{\"content\": \"run_command is sandboxed; no shell\"}"
                })
        public record Args(
                @SecurityHint(ParamCategory.CODE_CONTENT)
                        @Doc("The insight text to remember (already masked).")
                        String content,
                @SecurityHint(ParamCategory.GENERIC)
                        @Doc("Optional Session-LTM memory id to promote (curating boundary).")
                        String promoteMemoryId,
                @SecurityHint(ParamCategory.GENERIC)
                        @Doc("Optional project id to tag the insight with.")
                        String projectId) {}

        @Override
        public String getName() {
            return "write_insight";
        }

        @Override
        public String getDescription() {
            return "Write a new insight to Cross-Session LTM, or promote a Session-LTM memory to Cross-Session LTM. Self-edit, audited.";
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
    @ToolSecurity(risk = RiskCategory.FILE_WRITE)
    public static final class Forget implements NativeMcpTool<Forget.Args> {

        private final MemoryStore store;

        public Forget(MemoryStore store) {
            this.store = store;
        }

        @ToolDoc(
                description =
                        """
                        #### When to use
                        Use `forget` to explicitly drop a memory by id - when a memory is wrong, stale, \
                        duplicated, or the user asked to remove it. Works on Session-LTM or Cross-Session LTM \
                        memories. Use it for curation hygiene: keeping the memory store clean improves recall \
                        quality.

                        #### When NOT to use
                        - Do not use `forget` to "recall" - use `recall_session`/`recall_insights`.
                        - Do not use it to overwrite - use `write_insight` (forget + re-write if needed).
                        - Do not forget a memory you may need; forgetting is irreversible from the model's view.
                        - Do not forget a memory id you have not confirmed exists; recall first to get the id.

                        #### Behavior
                        Removes the memory identified by `memoryId` from the store (Session or Cross-Session, \
                        whichever owns it). The removal is audited. Other memories are untouched.

                        #### Return format
                        An acknowledgement (the memory was dropped). The write is audited.

                        #### Errors & edge cases
                        - `memoryId` does not exist -> typically a no-op / not-found acknowledgement; no error \
                        thrown.
                        - `memoryId` is case-sensitive and must match exactly (recall to confirm the id first).
                        - Forgetting is durable; the memory will not resurface unless re-captured/re-written.
                        - This is a self-edit operation; it is audited and elevated.

                        #### Security
                        `forget` is `RiskCategory.FILE_WRITE` (elevated + audited) - it mutates durable user \
                        memory. It operates only on the current user's store - no cross-tenant deletion. \
                        `memoryId` is GENERIC (no path/content danger). The deletion is logged for audit. Do \
                        not forget audit-relevant memories without reason.
                        """,
                examples = {
                    "{\"memoryId\": \"mem-42\"}",
                    "{\"memoryId\": \"insight-7\"}",
                    "{\"memoryId\": \"session-101\"}",
                    "{\"memoryId\": \"mem-99\"}",
                    "{\"memoryId\": \"insight-promoted-3\"}",
                    "{\"memoryId\": \"mem-1\"}"
                })
        public record Args(
                @SecurityHint(ParamCategory.GENERIC) @Doc("The memory id to forget.")
                        String memoryId) {}

        @Override
        public String getName() {
            return "forget";
        }

        @Override
        public String getDescription() {
            return "Explicitly drop a memory (user- or agent-initiated). Self-edit, audited.";
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
    static @org.jspecify.annotations.Nullable UUID parseUuidOrNull(
            @org.jspecify.annotations.Nullable String s) {
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
