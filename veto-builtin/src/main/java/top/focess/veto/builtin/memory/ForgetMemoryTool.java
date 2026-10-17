package top.focess.veto.builtin.memory;

import java.util.UUID;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.tool.Doc;
import top.focess.veto.api.agent.tool.ParamCategory;
import top.focess.veto.api.agent.tool.SecurityHint;
import top.focess.veto.api.agent.tool.ToolDoc;
import top.focess.veto.api.agent.tool.ToolErrorCode;
import top.focess.veto.api.agent.tool.ToolErrors;
import top.focess.veto.api.agent.tool.ToolResultFormat;

/** {@code forget_memory} — explicitly drop a memory. */
@ToolDoc(
        resultFormats = {ToolResultFormat.PLAINTEXT},
        description =
                """
                Explicitly drop a memory from the agent's long-term store.\
                """,
        behavior =
                """
                Permanently deletes the memory identified by `memoryId` from the store. It cannot be \
                recovered through this tool.\
                """,
        whenToUse =
                """
                Use `forget_memory` when a previously captured memory or insight is wrong, outdated, or no \
                longer relevant - correcting stale knowledge before it misleads future reasoning.\
                """,
        whenNotToUse =
                """
                - Do not use `forget_memory` to clear session context - that is automatic. - Do not use it \
                speculatively; only forget what you know is wrong. - Do not forget memories you have not \
                verified are incorrect.\
                """,
        resultContract =
                """
                - Success -> `forgotten: <memoryId>`. - Invalid, unknown, or cross-user id (failure, \
                MEMORY_NOT_FOUND): `Memory not found: the memory does not exist or is not owned; nothing forgotten.`\
                """,
        errorsAndEdgeCases =
                """
                Use an id returned by `recall_memory` or `write_memory`. Ownership and absence deliberately \
                share the contract's failure body so tenant isolation reveals nothing.\
                """,
        security =
                """
                Deletion is permanent. Remove only a memory you have verified should be removed.\
                """,
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
public final class ForgetMemoryTool extends MemoryWriteTool<ForgetMemoryTool.Args> {

    private final MemoryWriteCapability capability;

    /** Declaration-only instance; the host supplies the capability at execution time. */
    public ForgetMemoryTool() {
        this.capability = null;
    }

    /** Creates an instance bound to the given host capability. */
    public ForgetMemoryTool(@NonNull MemoryWriteCapability capability) {
        this.capability = capability;
    }

    /** Model-facing arguments of {@code forget_memory}. */
    public record Args(
            @SecurityHint(ParamCategory.GENERIC) @Doc("The memory id to forget.")
                    @NonNull String memoryId) {}

    @Override
    public @NonNull String getName() {
        return "forget_memory";
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
        String id = args.memoryId();
        if (id.isBlank()) {
            return ToolErrors.failure(
                    ToolErrorCode.MEMORY.MEMORY_NOT_FOUND,
                    "Memory not found: the memory does not exist or is not owned; nothing"
                            + " forgotten.");
        }
        try {
            MemoryId memoryId = new MemoryId(UUID.fromString(id.strip()));
            boolean forgotten = capability.forget(memoryId);
            return forgotten
                    ? "forgotten: " + memoryId.value()
                    : ToolErrors.failure(
                            ToolErrorCode.MEMORY.MEMORY_NOT_FOUND,
                            "Memory not found: the memory does not exist or is not owned;"
                                    + " nothing forgotten.");
        } catch (IllegalArgumentException e) {
            return ToolErrors.failure(
                    ToolErrorCode.MEMORY.MEMORY_NOT_FOUND,
                    "Memory not found: the memory does not exist or is not owned; nothing"
                            + " forgotten.");
        }
    }
}
