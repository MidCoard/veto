package top.focess.veto.memory;

import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.capability.MemoryReadCapability;
import top.focess.veto.agent.capability.MemoryWriteCapability;
import top.focess.veto.agent.tool.Doc;
import top.focess.veto.agent.tool.MemoryReadTool;
import top.focess.veto.agent.tool.MemoryWriteTool;
import top.focess.veto.agent.tool.ParamCategory;
import top.focess.veto.agent.tool.RequiredWhen;
import top.focess.veto.agent.tool.SecurityHint;
import top.focess.veto.agent.tool.ToolDoc;
import top.focess.veto.agent.tool.ToolDocs;
import top.focess.veto.agent.tool.ToolResultFormat;

/**
 * Agent-facing memory tools. Their agent-tool definition flavour means the Gateway returns {@code
 * NotScreened}. They still flow through the LoopInterceptor chain for audit.
 */
public final class MemoryTools {

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
    public static final class RecallMemory implements MemoryReadTool<RecallMemory.Args> {

        private final @NonNull MemoryReadCapability capability;

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
            return capability;
        }

        @Override
        public @NonNull String execute(
                @NonNull Args args, @NonNull MemoryReadCapability capability) {
            return capability.recall(args);
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
    public static final class WriteMemory implements MemoryWriteTool<WriteMemory.Args> {

        private final @NonNull MemoryWriteCapability capability;

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
            return capability;
        }

        @Override
        public @NonNull String execute(
                @NonNull Args args, @NonNull MemoryWriteCapability capability) {
            return capability.write(args);
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
    public static final class ForgetMemory implements MemoryWriteTool<ForgetMemory.Args> {

        private final @NonNull MemoryWriteCapability capability;

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
            return capability;
        }

        @Override
        public @NonNull String execute(
                @NonNull Args args, @NonNull MemoryWriteCapability capability) {
            return capability.forget(args);
        }
    }
}
