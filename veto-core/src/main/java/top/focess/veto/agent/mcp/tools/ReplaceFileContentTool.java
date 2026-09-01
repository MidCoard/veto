package top.focess.veto.agent.mcp.tools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.mcp.Doc;
import top.focess.veto.agent.mcp.NativeTool;
import top.focess.veto.agent.mcp.ParamCategory;
import top.focess.veto.agent.mcp.Required;
import top.focess.veto.agent.mcp.RiskCategory;
import top.focess.veto.agent.mcp.SecurityHint;
import top.focess.veto.agent.mcp.ToolCapability;
import top.focess.veto.agent.mcp.ToolDoc;
import top.focess.veto.agent.mcp.ToolDocs;
import top.focess.veto.agent.mcp.ToolErrors;
import top.focess.veto.agent.mcp.ToolJson;
import top.focess.veto.agent.mcp.ToolResultFormat;
import top.focess.veto.agent.mcp.ToolSecurity;

/** {@code replace_file_content} — replace a single contiguous block of text in an existing file. */
@Component
@ToolSecurity(risk = RiskCategory.FILE_WRITE, capability = ToolCapability.WORKSPACE_WRITE)
public final class ReplaceFileContentTool implements NativeTool<ReplaceFileContentTool.Args> {

    @ToolDoc(
            resultFormats = {ToolResultFormat.JSON},
            description = "Replace a single contiguous block of code in an existing file.",
            behavior =
                    """
                    Reads `absolutePath` as UTF-8 and searches only the inclusive `startLine`/`endLine` range. \
                    Exactly one occurrence of `targetContent` must exist inside that range. The updated content \
                    is written through a same-directory temporary file and replacement move. An empty \
                    `replacementContent` deletes the matched block. Both the original file and the resulting \
                    UTF-8 content are limited to 16 MiB (16,777,216 bytes). The limit bounds the complete \
                    in-memory edit and the temporary-file write performed by one call.
                    """,
            whenToUse =
                    """
                    Use `replace_file_content` to make a localized, surgical edit to an existing file - renaming \
                    a symbol in one spot, fixing a few lines, or swapping a block for new text. It targets a \
                    contiguous range and replaces the unique exact occurrence of `targetContent` within that \
                    selected range. Prefer it over `write_to_file` when most of the file is unchanged.

                    Always `view_file` the target range first so your `targetContent` matches the file exactly \
                    (whitespace included).
                    """,
            whenNotToUse =
                    """
                    - Do not use `replace_file_content` to create a file or rewrite most of it - use \
                    `write_to_file`.
                    - Do not use it blind - if `targetContent` does not match the file byte-for-byte, the call \
                    fails. Read first.
                    - Do not use it to replace ALL occurrences. For multiple \
                    sites, call it repeatedly or use `write_to_file`.
                    - Do not pass a `targetContent` so short it could match unintended locations (e.g. a bare \
                    `}`); include enough surrounding context to be unique.
                    """,
            resultContract =
                    """
                    - Success: `{"status":"ok","file":"<absolutePath>"}`.
                    - Invalid `absolutePath`, range, or replacement (failure): one of \
                    `Not a regular file: <absolutePath>`, `File exceeds 16 MiB (16,777,216 bytes)`, \
                    `Invalid line range`, `Line range outside file`, or \
                    `targetContent must not be empty` or \
                    `Replacement exceeds 16 MiB (16,777,216 bytes)`.
                    - Match failure (failure): \
                    `targetContent not found in selected range.` or \
                    `targetContent is not unique in selected range.` The file remains unchanged.
                    """,
            errorsAndEdgeCases =
                    """
                    - After a match failure, reread the selected range and quote enough surrounding context \
                    to make the target unique before retrying.
                    - Only regular files are editable; discover the target with `list_dir` and inspect it \
                    with `view_file` first.
                    - `targetContent` and `replacementContent` are exact (whitespace, indentation, newlines all \
                    matter). A mismatched indent means "not found".
                    - `startLine`/`endLine` must form a valid inclusive range and restrict the search.
                    - Replacing the directory entry can replace filesystem metadata or a symbolic-link entry; \
                    it does not write through a symbolic link to its target.
                    """,
            security =
                    """
                    `absolutePath` is a FILESYSTEM_PATH; `targetContent` and `replacementContent` are CODE_CONTENT. \
                    The Gateway screens the path and applies semantic screening to the replacement content \
                    before the write. The operation is `RiskCategory.FILE_WRITE` (elevated + audited). Do not \
                    attempt to patch deployer-fenced paths or smuggle disallowed content - the call is blocked \
                    upstream; change approach instead.
                    """,
            examples = {
                "{\"absolutePath\": \"/abs/src/Main.java\", \"startLine\": 5, \"endLine\": 8, \"targetContent\": \"old\", \"replacementContent\": \"new\"}",
                "{\"absolutePath\": \"/abs/src/Main.java\", \"startLine\": 12, \"endLine\": 12, \"targetContent\": \"int x = 1;\", \"replacementContent\": \"int x = 2;\"}",
                "{\"absolutePath\": \"/abs/src/Main.java\", \"startLine\": 1, \"endLine\": 1, \"targetContent\": \"package old;\", \"replacementContent\": \"package new;\"}",
                "{\"absolutePath\": \"/abs/README.md\", \"startLine\": 3, \"endLine\": 3, \"targetContent\": \"# Old Title\", \"replacementContent\": \"# New Title\"}",
                "{\"absolutePath\": \"/abs/config/app.yml\", \"startLine\": 10, \"endLine\": 10, \"targetContent\": \"port: 8080\", \"replacementContent\": \"port: 8443\"}",
                "{\"absolutePath\": \"/abs/src/Main.java\", \"startLine\": 20, \"endLine\": 24, \"targetContent\": \"// TODO\\n\", \"replacementContent\": \"// done\\n\"}",
                "{\"absolutePath\": \"/abs/src/Main.java\", \"startLine\": 8, \"endLine\": 8, \"targetContent\": \"    return null;\", \"replacementContent\": \"    return value;\"}"
            },
            returnExamples = {"{\"status\":\"ok\",\"file\":\"/abs/src/Main.java\"}"})
    public record Args(
            @SecurityHint(ParamCategory.FILESYSTEM_PATH) @Doc("Absolute path of the file to patch.")
                    @NonNull String absolutePath,
            @Required @Doc("1-indexed starting line (inclusive).") int startLine,
            @Required @Doc("1-indexed ending line (inclusive).") int endLine,
            @SecurityHint(ParamCategory.CODE_CONTENT) @Doc("Exact text range to replace.")
                    @NonNull String targetContent,
            @SecurityHint(ParamCategory.CODE_CONTENT) @Doc("The replacement text.")
                    @NonNull String replacementContent) {}

    @Override
    public @NonNull String getName() {
        return "replace_file_content";
    }

    @Override
    public @NonNull String getDescription() {
        return "Replace a single contiguous block of code in an existing file.";
    }

    @Override
    public @NonNull Class<Args> getArgsClass() {
        return ToolDocs.nonNullClass(Args.class);
    }

    @Override
    public @NonNull String execute(@NonNull Args args) throws IOException {
        Path path = Path.of(args.absolutePath());
        if (!Files.isRegularFile(path)) {
            return ToolErrors.failure("Not a regular file: " + args.absolutePath());
        }
        if (Files.size(path) > TextFileToolLimits.MAX_BYTES) {
            return ToolErrors.failure("File exceeds " + TextFileToolLimits.DISPLAY_SIZE);
        }
        if (args.startLine() < 1 || args.endLine() < args.startLine()) {
            return ToolErrors.failure("Invalid line range");
        }
        if (args.targetContent().isEmpty()) {
            return ToolErrors.failure("targetContent must not be empty");
        }
        String content = Files.readString(path, StandardCharsets.UTF_8);
        int rangeStart = lineStart(content, args.startLine());
        int rangeEnd = lineEnd(content, args.endLine());
        if (rangeStart < 0 || rangeEnd < rangeStart) {
            return ToolErrors.failure("Line range outside file");
        }
        int idx = content.indexOf(args.targetContent(), rangeStart);
        if (idx < 0 || idx + args.targetContent().length() > rangeEnd) {
            return ToolErrors.failure("targetContent not found in selected range.");
        }
        int next = content.indexOf(args.targetContent(), idx + 1);
        if (next >= 0 && next + args.targetContent().length() <= rangeEnd) {
            return ToolErrors.failure("targetContent is not unique in selected range.");
        }
        String updated =
                content.substring(0, idx)
                        + args.replacementContent()
                        + content.substring(idx + args.targetContent().length());
        byte[] updatedBytes = updated.getBytes(StandardCharsets.UTF_8);
        if (updatedBytes.length > TextFileToolLimits.MAX_BYTES) {
            return ToolErrors.failure("Replacement exceeds " + TextFileToolLimits.DISPLAY_SIZE);
        }
        AtomicFileWrites.write(path, updatedBytes, true);
        return ToolJson.object(Map.of("status", "ok", "file", args.absolutePath()));
    }

    private static int lineStart(@NonNull String content, int lineNumber) {
        if (lineNumber == 1) {
            return 0;
        }
        int currentLine = 1;
        for (int i = 0; i < content.length(); i++) {
            if (content.charAt(i) == '\n' && ++currentLine == lineNumber) {
                return i + 1;
            }
        }
        return -1;
    }

    private static int lineEnd(@NonNull String content, int lineNumber) {
        int start = lineStart(content, lineNumber);
        if (start < 0) {
            return -1;
        }
        int newline = content.indexOf('\n', start);
        return newline < 0 ? content.length() : newline + 1;
    }
}
