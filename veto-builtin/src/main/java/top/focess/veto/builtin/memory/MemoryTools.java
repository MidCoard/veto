package top.focess.veto.builtin.memory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.tool.Doc;
import top.focess.veto.api.agent.tool.ParamCategory;
import top.focess.veto.api.agent.tool.RequiredWhen;
import top.focess.veto.api.agent.tool.SecurityHint;
import top.focess.veto.api.agent.tool.ToolDoc;
import top.focess.veto.api.agent.tool.ToolDocs;
import top.focess.veto.api.agent.tool.ToolErrorCode;
import top.focess.veto.api.agent.tool.ToolErrors;
import top.focess.veto.api.agent.tool.ToolResultFormat;

/**
 * Agent-facing memory tools. Their agent-tool definition flavour means the Gateway returns {@code
 * NotScreened}. They still flow through the LoopInterceptor chain for audit.
 */
public final class MemoryTools {

    private MemoryTools() {}

    private static <T> @NonNull T requireValue(T value, @NonNull String message) {
        if (value == null) throw new IllegalArgumentException(message);
        return value;
    }

    private static final int MAX_MEMORY_CHARS = 64_000;

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

    /** {@code recall_memory} — search current-session memories and cross-session insights. */
    @ToolDoc(
            resultFormats = {ToolResultFormat.PLAINTEXT},
            description =
                    "Search the current session's captured memory and the user's cross-session insights together.",
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
                    `no matching memories`. Missing session context (failure, NO_SESSION_CONTEXT): \
                    `No session context: memories were not recalled.`
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
                "{\"query\": \"build configuration\"}",
                "{\"query\": \"quick brown fox\"}",
                "{\"query\": \"deployment rollback procedure\"}"
            },
            returnExamples = {
                "2 memories:\n- [CROSS_SESSION] id=123e4567-e89b-12d3-a456-426614174000 score=0.880 src=INSIGHT {}\n"
                        + "  Prefer constructor injection over field injection...\n"
                        + "- [SESSION] id=123e4567-e89b-12d3-a456-426614174001 score=0.820 src=turn_range {from=12, to=12}\n"
                        + "  UserService.authenticate validates the JWT expiry and...",
                "1 memories:\n- [CROSS_SESSION] id=123e4567-e89b-12d3-a456-426614174002 score=0.910 src=INSIGHT {}\n"
                        + "  This project uses Gradle 8.5 with Kotlin DSL...",
                "1 memories:\n- [CROSS_SESSION] id=ffde62f9-716f-41e4-bcec-8d63fbf8ed7c score=0.900 src=stored {raw=insight_origin {origin=write_memory}}\n"
                        + "  test placeholder\n",
                "no matching memories"
            })
    public static final class RecallMemory implements MemoryReadTool<RecallMemory.Args> {

        private final MemoryReadCapability capability;

        public RecallMemory() {
            this.capability = null;
        }

        public RecallMemory(@NonNull MemoryReadCapability capability) {
            this.capability = capability;
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
        public @NonNull MemoryReadCapability memoryReadCapability() {
            if (capability == null) throw new SecurityException("Host must supply tool capability");
            return capability;
        }

        @Override
        public @NonNull String execute(
                @NonNull Args args, @NonNull MemoryReadCapability capability) {
            String query =
                    args.query().length() <= 4000 ? args.query() : args.query().substring(0, 4000);
            List<ScoredMemory> matches = new ArrayList<>();
            matches.addAll(capability.search(query, MemoryTier.SESSION, 5, 0.5f));
            matches.addAll(capability.search(query, MemoryTier.CROSS_SESSION, 5, 0.5f));
            matches.sort(Comparator.comparingDouble(ScoredMemory::score).reversed());
            return formatResults(matches.subList(0, Math.min(5, matches.size())));
        }
    }

    /** {@code write_memory} — write durable memory or promote a Session-LTM item. */
    @ToolDoc(
            resultFormats = {ToolResultFormat.PLAINTEXT},
            description =
                    "Write durable cross-session memory, or promote a Session-LTM memory to cross-session visibility.",
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
                    Use `write_memory` when the user requests future recall or verified durable knowledge clearly benefits future sessions - \
                    project conventions, recurring patterns, architectural decisions, or lessons \
                    learned. Also use it to promote a Session LTM memory to Cross-Session LTM when \
                    its value extends beyond this session.
                    """,
            whenNotToUse =
                    """
                    - Do not use `write_memory` for transient context that only matters this session - \
                    Conversation history already retains task context. Answering or citing existing context needs no memory write.
                    - Do not use it to record verbatim file contents - reference the file path instead.
                    - Do not write trivial or obvious facts; insights should be non-obvious, reusable \
                    knowledge.
                    """,
            resultContract =
                    """
                    - Direct-write success: \
                    `memory written: <memory UUID>`.
                    - Promotion success: `promoted: <new memory UUID>`.
                    - Promotion failure (failure, NOT_FOUND): \
                    `Memory not found: the memory does not exist or is not owned; not promoted.`
                    - Write failures: too-large content (failure, TOO_LARGE): \
                    `Memory too large: the content exceeds 64000 characters; memory not written.`; \
                    invalid project id (failure, INVALID_ARGUMENTS): \
                    `Invalid arguments: projectId must be a UUID; memory not written.`
                    - Mode-field mismatch (failure, INVALID_ARGUMENTS): \
                    `Invalid arguments: PROMOTE accepts only promoteMemoryId; memory not promoted.` \
                    or `Invalid arguments: WRITE does not accept promoteMemoryId; memory not written.`
                    """,
            errorsAndEdgeCases =
                    """
                    `WRITE` accepts content plus an optional project id; `PROMOTE` accepts only a memory id. \
                    Correct a mode/field mismatch before retrying. Ownership and absence deliberately share a \
                    promotion failure so tenant isolation leaks nothing. Never store secrets or verbatim file \
                    contents in durable memory.
                    """,
            security =
                    "Content is stored as supplied and persists across sessions. Never include secrets.",
            examples = {
                "{\"mode\": \"WRITE\", \"content\": \"topic: evidence-demo\\nThe quick brown fox jumps over the lazy dog\"}",
                "{\"mode\": \"WRITE\", \"content\": \"Decision: audit records are append-only; corrections are written as new compensating entries, never edits.\"}",
                "{\"mode\": \"WRITE\", \"content\": \"Prefer constructor injection\", \"projectId\": \"123e4567-e89b-12d3-a456-426614174000\"}",
                "{\"mode\": \"PROMOTE\", \"promoteMemoryId\": \"123e4567-e89b-12d3-a456-426614174000\"}",
                "{\"mode\": \"PROMOTE\", \"promoteMemoryId\": \"123e4567-e89b-12d3-a456-426614174000\", \"content\": \"Replacement content that PROMOTE rejects\"}"
            },
            returnExamples = {
                "memory written: af7730d5-47ab-4e63-b61c-3fda7777b5a0",
                "memory written: 123e4567-e89b-12d3-a456-426614174001",
                "memory written: 123e4567-e89b-12d3-a456-426614174002",
                "promoted: 123e4567-e89b-12d3-a456-426614174003",
                "Invalid arguments: PROMOTE accepts only promoteMemoryId; memory not promoted."
            })
    public static final class WriteMemory implements MemoryWriteTool<WriteMemory.Args> {

        private final MemoryWriteCapability capability;

        public WriteMemory() {
            this.capability = null;
        }

        public WriteMemory(@NonNull MemoryWriteCapability capability) {
            this.capability = capability;
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
        public @NonNull MemoryWriteCapability memoryWriteCapability() {
            if (capability == null) throw new SecurityException("Host must supply tool capability");
            return capability;
        }

        @Override
        public @NonNull String execute(
                @NonNull Args args, @NonNull MemoryWriteCapability capability) {
            String requestedContent = args.content();
            String requestedProjectId = args.projectId();
            String requestedPromoteId = args.promoteMemoryId();
            if (args.mode() == WriteMemory.Mode.PROMOTE) {
                if ((requestedContent != null && !requestedContent.isBlank())
                        || (requestedProjectId != null && !requestedProjectId.isBlank())) {
                    return ToolErrors.failure(
                            ToolErrorCode.VALIDATION.INVALID_ARGUMENTS,
                            "Invalid arguments: PROMOTE accepts only promoteMemoryId; memory not promoted.");
                }
                String promoteId =
                        requireValue(
                                requestedPromoteId,
                                "RequiredWhen validation must supply promoteMemoryId");
                try {
                    MemoryId promoted =
                            capability.promote(new MemoryId(UUID.fromString(promoteId.strip())));
                    return promoted != null
                            ? "promoted: " + promoted.value()
                            : ToolErrors.failure(
                                    ToolErrorCode.MEMORY.NOT_FOUND,
                                    "Memory not found: the memory does not exist or is not owned; not promoted.");
                } catch (IllegalArgumentException e) {
                    return ToolErrors.failure(
                            ToolErrorCode.MEMORY.NOT_FOUND,
                            "Memory not found: the memory does not exist or is not owned; not promoted.");
                }
            }
            if (requestedPromoteId != null && !requestedPromoteId.isBlank()) {
                return ToolErrors.failure(
                        ToolErrorCode.VALIDATION.INVALID_ARGUMENTS,
                        "Invalid arguments: WRITE does not accept promoteMemoryId; memory not written.");
            }
            String content =
                    requireValue(requestedContent, "RequiredWhen validation must supply content");
            if (content.length() > MAX_MEMORY_CHARS) {
                return ToolErrors.failure(
                        ToolErrorCode.MEMORY.TOO_LARGE,
                        "Memory too large: the content exceeds 64000 characters; memory not written.");
            }
            UUID projectId = parseUuidOrNull(requestedProjectId);
            if (requestedProjectId != null && !requestedProjectId.isBlank() && projectId == null) {
                return ToolErrors.failure(
                        ToolErrorCode.VALIDATION.INVALID_ARGUMENTS,
                        "Invalid arguments: projectId must be a UUID; memory not written.");
            }
            MemoryId id = capability.add(content, projectId);
            return "memory written: " + id.value();
        }
    }

    /** {@code forget_memory} — explicitly drop a memory. */
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
                    - Invalid, unknown, or cross-user id (failure, NOT_FOUND): \
                    `Memory not found: the memory does not exist or is not owned; nothing forgotten.`
                    """,
            errorsAndEdgeCases =
                    """
                    Use an id returned by `recall_memory` or `write_memory`. Ownership and absence deliberately \
                    share the contract's failure body so tenant isolation reveals nothing.
                    """,
            security =
                    "Deletion is permanent. Remove only a memory you have verified should be removed.",
            examples = {
                "{\"memoryId\": \"af7730d5-47ab-4e63-b61c-3fda7777b5a0\"}",
                "{\"memoryId\": \"123e4567-e89b-12d3-a456-426614174001\"}",
                "{\"memoryId\": \"00000000-0000-0000-0000-000000000000\"}"
            },
            returnExamples = {
                "forgotten: af7730d5-47ab-4e63-b61c-3fda7777b5a0",
                "forgotten: 123e4567-e89b-12d3-a456-426614174001",
                "Memory not found: the memory does not exist or is not owned; nothing forgotten."
            })
    public static final class ForgetMemory implements MemoryWriteTool<ForgetMemory.Args> {

        private final MemoryWriteCapability capability;

        public ForgetMemory() {
            this.capability = null;
        }

        public ForgetMemory(@NonNull MemoryWriteCapability capability) {
            this.capability = capability;
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
        public @NonNull MemoryWriteCapability memoryWriteCapability() {
            if (capability == null) throw new SecurityException("Host must supply tool capability");
            return capability;
        }

        @Override
        public @NonNull String execute(
                @NonNull Args args, @NonNull MemoryWriteCapability capability) {
            String id = args.memoryId();
            if (id.isBlank()) {
                return ToolErrors.failure(
                        ToolErrorCode.MEMORY.NOT_FOUND,
                        "Memory not found: the memory does not exist or is not owned; nothing forgotten.");
            }
            try {
                MemoryId memoryId = new MemoryId(UUID.fromString(id.strip()));
                boolean forgotten = capability.forget(memoryId);
                return forgotten
                        ? "forgotten: " + memoryId.value()
                        : ToolErrors.failure(
                                ToolErrorCode.MEMORY.NOT_FOUND,
                                "Memory not found: the memory does not exist or is not owned; nothing forgotten.");
            } catch (IllegalArgumentException e) {
                return ToolErrors.failure(
                        ToolErrorCode.MEMORY.NOT_FOUND,
                        "Memory not found: the memory does not exist or is not owned; nothing forgotten.");
            }
        }
    }
}
