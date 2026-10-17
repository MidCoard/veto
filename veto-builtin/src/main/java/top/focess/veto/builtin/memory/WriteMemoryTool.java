package top.focess.veto.builtin.memory;

import java.util.UUID;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.tool.Doc;
import top.focess.veto.api.agent.tool.ParamCategory;
import top.focess.veto.api.agent.tool.RequiredWhen;
import top.focess.veto.api.agent.tool.SecurityHint;
import top.focess.veto.api.agent.tool.ToolDoc;
import top.focess.veto.api.agent.tool.ToolErrorCode;
import top.focess.veto.api.agent.tool.ToolErrors;
import top.focess.veto.api.agent.tool.ToolResultFormat;

/** {@code write_memory} — write durable memory or promote a Session-LTM item. */
@ToolDoc(
        resultFormats = {ToolResultFormat.PLAINTEXT},
        description =
                """
                Write durable cross-session memory, or promote a Session-LTM memory to cross-session \
                visibility.\
                """,
        behavior =
                """
                Set `mode` to `WRITE` to store `content` as new durable Cross-Session memory, tagged with a \
                UUID `projectId` when provided. Set `mode` to `PROMOTE` and provide only `promoteMemoryId` to \
                replace an existing Session-LTM memory with a new Cross-Session memory. Non-blank fields from \
                the other mode are rejected. A successful promotion invalidates the old id and returns the \
                replacement id.\
                """,
        whenToUse =
                """
                Use `write_memory` when the user requests future recall or verified durable knowledge clearly \
                benefits future sessions - project conventions, recurring patterns, architectural decisions, \
                or lessons learned. Also use it to promote a Session LTM memory to Cross-Session LTM when its \
                value extends beyond this session.\
                """,
        whenNotToUse =
                """
                - Do not use `write_memory` for transient context that only matters this session - \
                Conversation history already retains task context. Answering or citing existing context needs \
                no memory write. - Do not use it to record verbatim file contents - reference the file path \
                instead. - Do not write trivial or obvious facts; insights should be non-obvious, reusable \
                knowledge.\
                """,
        resultContract =
                """
                - Direct-write success: `memory written: <memory UUID>`. - Promotion success: `promoted: <new \
                memory UUID>`. - Promotion failure (failure, MEMORY_NOT_FOUND): `Memory not found: the memory does \
                not exist or is not owned; not promoted.` - Write failures: too-large content (failure, \
                FILE_TOO_LARGE): `Memory too large: the content exceeds 64000 characters; memory not written.`; \
                invalid project id (failure, INVALID_ARGUMENTS): `Invalid arguments: projectId must be a \
                UUID; memory not written.` - Mode-field mismatch (failure, INVALID_ARGUMENTS): `Invalid \
                arguments: PROMOTE accepts only promoteMemoryId; memory not promoted.` or `Invalid arguments: \
                WRITE does not accept promoteMemoryId; memory not written.`\
                """,
        errorsAndEdgeCases =
                """
                `WRITE` accepts content plus an optional project id; `PROMOTE` accepts only a memory id. \
                Correct a mode/field mismatch before retrying. Ownership and absence deliberately share a \
                promotion failure so tenant isolation leaks nothing. Never store secrets or verbatim file \
                contents in durable memory.\
                """,
        security =
                """
                Content is stored as supplied and persists across sessions. Never include secrets.\
                """,
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
public final class WriteMemoryTool extends MemoryWriteTool<WriteMemoryTool.Args> {

    private final MemoryWriteCapability capability;

    /** Declaration-only instance; the host supplies the capability at execution time. */
    public WriteMemoryTool() {
        this.capability = null;
    }

    /** Creates an instance bound to the given host capability. */
    public WriteMemoryTool(@NonNull MemoryWriteCapability capability) {
        this.capability = capability;
    }

    /** Operation selected by the model: store new content or promote an existing memory. */
    public enum Mode {
        WRITE,
        PROMOTE
    }

    /** Model-facing arguments of {@code write_memory}. */
    public record Args(
            @Doc("Required operation: WRITE new durable memory or PROMOTE existing memory.")
                    @NonNull Mode mode,
            @SecurityHint(ParamCategory.GENERIC)
                    @Doc(
                            "Durable memory text; required only in WRITE mode. Never include"
                                    + " secrets.")
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
        return Args.class;
    }

    @Override
    public @NonNull MemoryWriteCapability memoryWriteCapability() {
        if (capability == null) throw new SecurityException("Host must supply tool capability");
        return capability;
    }

    @Override
    public @NonNull String execute(@NonNull Args args, @NonNull MemoryWriteCapability capability) {
        String requestedContent = args.content();
        String requestedProjectId = args.projectId();
        String requestedPromoteId = args.promoteMemoryId();
        if (args.mode() == WriteMemoryTool.Mode.PROMOTE) {
            if ((requestedContent != null && !requestedContent.isBlank())
                    || (requestedProjectId != null && !requestedProjectId.isBlank())) {
                return ToolErrors.failure(
                        ToolErrorCode.VALIDATION.INVALID_ARGUMENTS,
                        "Invalid arguments: PROMOTE accepts only promoteMemoryId; memory not"
                                + " promoted.");
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
                                ToolErrorCode.MEMORY.MEMORY_NOT_FOUND,
                                "Memory not found: the memory does not exist or is not owned;"
                                        + " not promoted.");
            } catch (IllegalArgumentException e) {
                return ToolErrors.failure(
                        ToolErrorCode.MEMORY.MEMORY_NOT_FOUND,
                        "Memory not found: the memory does not exist or is not owned; not"
                                + " promoted.");
            }
        }
        if (requestedPromoteId != null && !requestedPromoteId.isBlank()) {
            return ToolErrors.failure(
                    ToolErrorCode.VALIDATION.INVALID_ARGUMENTS,
                    "Invalid arguments: WRITE does not accept promoteMemoryId; memory not"
                            + " written.");
        }
        String content =
                requireValue(requestedContent, "RequiredWhen validation must supply content");
        if (content.length() > MAX_MEMORY_CHARS) {
            return ToolErrors.failure(
                    ToolErrorCode.WORKSPACE.FILE_TOO_LARGE,
                    "Memory too large: the content exceeds 64000 characters; memory not"
                            + " written.");
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

    private static <T> @NonNull T requireValue(T value, @NonNull String message) {
        if (value == null) throw new IllegalArgumentException(message);
        return value;
    }

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

    private static final int MAX_MEMORY_CHARS = 64_000;
}
