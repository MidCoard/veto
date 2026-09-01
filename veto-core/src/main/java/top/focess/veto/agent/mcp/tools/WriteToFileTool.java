package top.focess.veto.agent.mcp.tools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.mcp.Doc;
import top.focess.veto.agent.mcp.NativeTool;
import top.focess.veto.agent.mcp.ParamCategory;
import top.focess.veto.agent.mcp.RiskCategory;
import top.focess.veto.agent.mcp.SecurityHint;
import top.focess.veto.agent.mcp.ToolCapability;
import top.focess.veto.agent.mcp.ToolDoc;
import top.focess.veto.agent.mcp.ToolDocs;
import top.focess.veto.agent.mcp.ToolErrors;
import top.focess.veto.agent.mcp.ToolJson;
import top.focess.veto.agent.mcp.ToolResultFormat;
import top.focess.veto.agent.mcp.ToolSecurity;

/** {@code write_to_file} — create a new file or completely overwrite an existing file. */
@Component
@ToolSecurity(risk = RiskCategory.FILE_WRITE, capability = ToolCapability.WORKSPACE_WRITE)
public final class WriteToFileTool implements NativeTool<WriteToFileTool.Args> {

    private static final int MAX_CONTENT_BYTES = 16 * 1024 * 1024;

    @ToolDoc(
            resultFormats = {ToolResultFormat.JSON},
            description = "Create a new file or completely overwrite an existing file.",
            usage =
                    """
                    #### When to use
                    Use `write_to_file` to create a new file or to completely replace an existing file's \
                    contents with new text - authoring a new source file, regenerating a file from scratch, or \
                    replacing a file whose contents are mostly changing. Pass the full intended content; the \
                    tool writes it verbatim.

                    #### When NOT to use
                    - Do not use `write_to_file` for a small, localized change to an existing file - use \
                    `replace_file_content` (it targets a line range and is safer for surgical edits).
                    - Do not use it to append - it overwrites. There is no append mode.
                    - Do not pass a partial file expecting the rest to be preserved; the entire file becomes \
                    exactly `codeContent`.
                    - Do not use it to inspect a file first - read with `view_file`, then decide.

                    #### Behavior
                    Writes `codeContent` to `absolutePath` as UTF-8. When `overwrite` is false and the file \
                    already exists, the write is refused (no partial write). When the file does not exist, \
                    parent directories are created as needed and the file is created. When `overwrite` is true, \
                    the tool writes a same-directory temporary file and replaces the target directory entry.

                    #### Return format
                    - Success: \
                    `{"status":"ok","file":"<path>","bytes":<byteCount>}`, where `byteCount` is \
                    the UTF-8 byte length written.
                    - Oversized content (failure): \
                    `Content exceeds 16777216 bytes`.
                    - Existing target with overwrite disabled (failure): \
                    `File exists and overwrite=false: <path>`.

                    #### Errors & edge cases
                    - Parent creation, temporary-file, disk, or move failures produce a failed tool result and \
                    do not count as success.
                    - `codeContent` is written byte-for-byte; an empty string creates an empty file.
                    - Replacing a target may replace its filesystem metadata and replaces a symbolic-link entry \
                    rather than writing through to the link target.

                    #### Security
                    `absolutePath` is a FILESYSTEM_PATH and `codeContent` is CODE_CONTENT: the Gateway screens the \
                    path against the deployer policy and allowed roots, and applies semantic screening to the \
                    content before the write. The operation is `RiskCategory.FILE_WRITE` (elevated + audited). \
                    Do not attempt to write outside allowed roots or to deployer-fenced paths - the call is \
                    blocked upstream; change approach instead. Do not embed high-value secrets in written files.
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
            @Doc("If false, refuse to overwrite an existing file.") boolean overwrite) {}

    @Override
    public @NonNull String getName() {
        return "write_to_file";
    }

    @Override
    public @NonNull String getDescription() {
        return "Create a new file or completely overwrite an existing file.";
    }

    @Override
    public @NonNull Class<Args> getArgsClass() {
        return ToolDocs.nonNullClass(Args.class);
    }

    @Override
    public @NonNull String execute(@NonNull Args args) throws IOException {
        Path path = Path.of(args.absolutePath());
        byte[] content = args.codeContent().getBytes(StandardCharsets.UTF_8);
        if (content.length > MAX_CONTENT_BYTES) {
            return ToolErrors.failure("Content exceeds 16777216 bytes");
        }
        if (!args.overwrite() && Files.exists(path)) {
            return ToolErrors.failure("File exists and overwrite=false: " + args.absolutePath());
        }
        try {
            AtomicFileWrites.write(path, content, args.overwrite());
        } catch (FileAlreadyExistsException e) {
            return ToolErrors.failure("File exists and overwrite=false: " + args.absolutePath());
        }
        return ToolJson.object(
                Map.of("status", "ok", "file", args.absolutePath(), "bytes", content.length));
    }
}
