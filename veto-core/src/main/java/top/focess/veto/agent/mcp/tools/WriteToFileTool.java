package top.focess.veto.agent.mcp.tools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.mcp.Doc;
import top.focess.veto.agent.mcp.NativeTool;
import top.focess.veto.agent.mcp.ParamCategory;
import top.focess.veto.agent.mcp.RiskCategory;
import top.focess.veto.agent.mcp.SecurityHint;
import top.focess.veto.agent.mcp.ToolDoc;
import top.focess.veto.agent.mcp.ToolSecurity;

/** {@code write_to_file} — create a new file or completely overwrite an existing file. */
@Component
@ToolSecurity(risk = RiskCategory.FILE_WRITE)
public final class WriteToFileTool implements NativeTool<WriteToFileTool.Args> {

    @ToolDoc(
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
                    Writes `codeContent` to `targetFile` as UTF-8. When `overwrite` is false and the file \
                    already exists, the write is refused (no partial write). When the file does not exist, \
                    parent directories are created as needed and the file is created. When `overwrite` is true, \
                    an existing file is truncated and replaced with the new content.

                    #### Return format
                    On success: `{"status":"ok","file":"<path>","bytes":<byteCount>}` where `byteCount` is the \
                    UTF-8 byte length written. On refusal: \
                    `{"status":"error","error":"File exists and overwrite=false: <path>"}`.

                    #### Errors & edge cases
                    - File exists and `overwrite` is false -> error status (see above); nothing is written.
                    - Parent directory cannot be created (permissions, an existing non-directory entry) -> the \
                    underlying IO error propagates.
                    - `codeContent` is written byte-for-byte; an empty string creates an empty file.
                    - `overwrite` is a primitive boolean (required, not optional) - always state it explicitly.

                    #### Security
                    `targetFile` is a FILESYSTEM_PATH and `codeContent` is CODE_CONTENT: the Gateway screens the \
                    path against the deployer policy and allowed roots, and applies semantic screening to the \
                    content before the write. The operation is `RiskCategory.FILE_WRITE` (elevated + audited). \
                    Do not attempt to write outside allowed roots or to deployer-fenced paths - the call is \
                    blocked upstream; change approach instead. Do not embed high-value secrets in written files.
                    """,
            examples = {
                "{\"targetFile\": \"/abs/src/Main.java\", \"codeContent\": \"package x;\\n\", \"overwrite\": false}",
                "{\"targetFile\": \"/abs/src/Main.java\", \"codeContent\": \"package x;\\npublic class Main {}\\n\", \"overwrite\": true}",
                "{\"targetFile\": \"/abs/notes/todo.md\", \"codeContent\": \"# Todo\\n- [ ] x\\n\", \"overwrite\": false}",
                "{\"targetFile\": \"/abs/src/util/Helper.java\", \"codeContent\": \"package util;\\npublic class Helper {}\\n\", \"overwrite\": false}",
                "{\"targetFile\": \"/abs/empty.txt\", \"codeContent\": \"\", \"overwrite\": true}",
                "{\"targetFile\": \"/abs/config/local.properties\", \"codeContent\": \"debug=true\\n\", \"overwrite\": false}",
                "{\"targetFile\": \"/abs/src/Main.java\", \"codeContent\": \"// rewritten\\n\", \"overwrite\": true}"
            },
            returnExamples = {"{\"status\":\"ok\",\"file\":\"/abs/src/Main.java\"}"})
    public record Args(
            @SecurityHint(ParamCategory.FILESYSTEM_PATH) @Doc("Absolute path of the file to write.")
                    @NonNull String targetFile,
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
        return Args.class;
    }

    @Override
    public @NonNull String execute(@NonNull Args args) throws IOException {
        Path path = Path.of(args.targetFile());
        if (!args.overwrite() && Files.exists(path)) {
            return "{\"status\":\"error\",\"error\":\"File exists and overwrite=false: "
                    + args.targetFile()
                    + "\"}";
        }
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        Files.writeString(path, args.codeContent(), StandardCharsets.UTF_8);
        return "{\"status\":\"ok\",\"file\":\""
                + args.targetFile()
                + "\",\"bytes\":"
                + args.codeContent().getBytes(StandardCharsets.UTF_8).length
                + "}";
    }
}
