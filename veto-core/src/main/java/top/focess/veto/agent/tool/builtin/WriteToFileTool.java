package top.focess.veto.agent.tool.builtin;

import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.capability.WorkspaceWriteCapability;
import top.focess.veto.agent.screening.Danger;
import top.focess.veto.agent.tool.Doc;
import top.focess.veto.agent.tool.ParamCategory;
import top.focess.veto.agent.tool.Required;
import top.focess.veto.agent.tool.SecurityHint;
import top.focess.veto.agent.tool.ToolCapability;
import top.focess.veto.agent.tool.ToolDoc;
import top.focess.veto.agent.tool.ToolDocs;
import top.focess.veto.agent.tool.ToolResultFormat;
import top.focess.veto.agent.tool.ToolSecurity;
import top.focess.veto.agent.tool.WorkspaceWriteTool;

/** {@code write_to_file} — create a new file or completely overwrite an existing file. */
@Component
@ToolSecurity(capability = ToolCapability.WORKSPACE_WRITE, defaultDanger = Danger.ELEVATED)
public final class WriteToFileTool implements WorkspaceWriteTool<WriteToFileTool.Args> {

    @ToolDoc(
            resultFormats = {ToolResultFormat.JSON},
            description = "Create a new file or completely overwrite an existing file.",
            behavior =
                    """
                    Writes `codeContent` to `absolutePath` as UTF-8. When `overwrite` is false and the file \
                    already exists, the write is refused (no partial write). When the file does not exist, \
                    parent directories are created as needed and the file is created. When `overwrite` is true, \
                    the tool writes a same-directory temporary file and replaces the target directory entry. \
                    The UTF-8 encoded `codeContent` is limited to 16 MiB (16,777,216 bytes), checked before \
                    directories or temporary files are created. This bounds memory and filesystem use per call.
                    """,
            whenToUse =
                    """
                    Use `write_to_file` to create a new file or to completely replace an existing file's \
                    contents with new text - authoring a new source file, regenerating a file from scratch, or \
                    replacing a file whose contents are mostly changing. Pass the full intended content; the \
                    tool writes it verbatim.
                    """,
            whenNotToUse =
                    """
                    - Do not use `write_to_file` for a small, localized change to an existing file - use \
                    `replace_file_content` (it targets a line range and is safer for surgical edits).
                    - Do not use it to append - it overwrites. There is no append mode.
                    - Do not pass a partial file expecting the rest to be preserved; the entire file becomes \
                    exactly `codeContent`.
                    - Do not use it to inspect a file first - read with `view_file`, then decide.
                    """,
            resultContract =
                    """
                    - Success: \
                    `{"status":"ok","file":"<absolutePath>","bytes":<byteCount>}`, where `byteCount` is \
                    the UTF-8 byte length written.
                    - Oversized content (failure): \
                    `Content exceeds 16 MiB (16,777,216 bytes)`.
                    - Existing `absolutePath` with overwrite disabled (failure): \
                    `File exists and overwrite=false: <absolutePath>`.
                    """,
            errorsAndEdgeCases =
                    """
                    - Parent creation, temporary-file, disk, or move failures produce a failed tool result and \
                    do not count as success.
                    - `codeContent` is written byte-for-byte; an empty string creates an empty file.
                    - Replacing a target may replace its filesystem metadata. Symbolic-link and Windows \
                    reparse-point targets are rejected; the tool does not write through them or replace them.
                    """,
            security =
                    """
                    `absolutePath` is a FILESYSTEM_PATH and `codeContent` is CODE_CONTENT: the Gateway canonicalizes \
                    the path, screens it under the deployer policy, and applies semantic screening to the content \
                    before the write. Under FULL_ACCESS, workspace roots are working context rather than a path \
                    boundary, so any absolute host path may be targeted; restrictive policies may fence paths. \
                    The Gateway exposes the screened target through a call-scoped `WORKSPACE_WRITE` capability. \
                    The operation has default danger `ELEVATED`; it is audited and may require approval. If the \
                    Gateway actually refuses a path, change approach instead. Do not embed high-value secrets \
                    in written files.
                    """,
            examples = {
                "{\"absolutePath\": \"/abs/src/Main.java\", \"codeContent\": \"package x;\\n\", \"overwrite\": false}",
                "{\"absolutePath\": \"/abs/src/Main.java\", \"codeContent\": \"package x;\\npublic class Main {}\\n\", \"overwrite\": true}",
                "{\"absolutePath\": \"/abs/notes/todo.md\", \"codeContent\": \"# Todo\\n- [ ] x\\n\", \"overwrite\": false}",
                "{\"absolutePath\": \"/abs/src/util/Helper.java\", \"codeContent\": \"package util;\\npublic class Helper {}\\n\", \"overwrite\": false}",
                "{\"absolutePath\": \"/abs/empty.txt\", \"codeContent\": \"\", \"overwrite\": true}",
                "{\"absolutePath\": \"/abs/config/local.properties\", \"codeContent\": \"debug=true\\n\", \"overwrite\": false}",
                "{\"absolutePath\": \"/abs/src/Main.java\", \"codeContent\": \"// rewritten\\n\", \"overwrite\": true}"
            },
            returnExamples = {"{\"status\":\"ok\",\"file\":\"/abs/src/Main.java\",\"bytes\":128}"})
    public record Args(
            @SecurityHint(ParamCategory.FILESYSTEM_PATH) @Doc("Absolute path of the file to write.")
                    @NonNull String absolutePath,
            @SecurityHint(ParamCategory.CODE_CONTENT) @Doc("The full content to write.")
                    @NonNull String codeContent,
            @Required @Doc("If false, refuse to overwrite an existing file.") boolean overwrite) {}

    @Override
    public @NonNull String getName() {
        return "write_to_file";
    }

    @Override
    public @NonNull Class<Args> getArgsClass() {
        return ToolDocs.nonNullClass(Args.class);
    }

    @Override
    public @NonNull String execute(
            @NonNull Args args, @NonNull WorkspaceWriteCapability capability) {
        return capability.writeText("absolutePath", args.codeContent(), args.overwrite());
    }
}
