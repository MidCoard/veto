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
import top.focess.veto.agent.mcp.ToolCapability;
import top.focess.veto.agent.mcp.ToolDoc;
import top.focess.veto.agent.mcp.ToolDocs;
import top.focess.veto.agent.mcp.ToolSecurity;

/** {@code replace_file_content} — replace a single contiguous block of text in an existing file. */
@Component
@ToolSecurity(risk = RiskCategory.FILE_WRITE, capability = ToolCapability.WORKSPACE_WRITE)
public final class ReplaceFileContentTool implements NativeTool<ReplaceFileContentTool.Args> {

    private static final long MAX_FILE_BYTES = 16L * 1024L * 1024L;

    @ToolDoc(
            description = "Replace a single contiguous block of code in an existing file.",
            usage =
                    """
                    #### When to use
                    Use `replace_file_content` to make a localized, surgical edit to an existing file - renaming \
                    a symbol in one spot, fixing a few lines, or swapping a block for new text. It targets a \
                    contiguous range and replaces the unique exact occurrence of `targetContent` within that \
                    selected range. Prefer it over `write_to_file` when most of the file is unchanged.

                    Always `view_file` the target range first so your `targetContent` matches the file exactly \
                    (whitespace included).

                    #### When NOT to use
                    - Do not use `replace_file_content` to create a file or rewrite most of it - use \
                    `write_to_file`.
                    - Do not use it blind - if `targetContent` does not match the file byte-for-byte, the call \
                    fails. Read first.
                    - Do not use it to replace ALL occurrences. For multiple \
                    sites, call it repeatedly or use `write_to_file`.
                    - Do not pass a `targetContent` so short it could match unintended locations (e.g. a bare \
                    `}`); include enough surrounding context to be unique.

                    #### Behavior
                    Reads `absolutePath` as UTF-8 and searches only the inclusive `startLine`/`endLine` range. \
                    Exactly one occurrence of `targetContent` must exist inside that range. The updated content \
                    is written through a same-directory temporary file and replacement move.

                    #### Return format
                    On success: `{"status":"ok","file":"<path>"}`. On failure to find the target: \
                    `{"status":"error","error":"targetContent not found in file."}`. On a non-file path: \
                    `{"status":"error","error":"Not a regular file: <path>"}`.

                    #### Errors & edge cases
                    - `targetContent` not present -> error status; the file is left untouched.
                    - `absolutePath` is not a regular file -> error status.
                    - Zero or multiple matches in the selected range are refused.
                    - `targetContent` and `replacementContent` are exact (whitespace, indentation, newlines all \
                    matter). A mismatched indent means "not found".
                    - `startLine`/`endLine` must form a valid inclusive range and restrict the search.

                    #### Security
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
            @Doc("1-indexed starting line (inclusive).") int startLine,
            @Doc("1-indexed ending line (inclusive).") int endLine,
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
            return "{\"status\":\"error\",\"error\":\"Not a regular file: "
                    + args.absolutePath()
                    + "\"}";
        }
        if (Files.size(path) > MAX_FILE_BYTES) {
            return "{\"status\":\"error\",\"error\":\"File exceeds 16777216 bytes\"}";
        }
        if (args.startLine() < 1 || args.endLine() < args.startLine()) {
            return "{\"status\":\"error\",\"error\":\"Invalid line range\"}";
        }
        if (args.targetContent().isEmpty()) {
            return "{\"status\":\"error\",\"error\":\"targetContent must not be empty\"}";
        }
        String content = Files.readString(path, StandardCharsets.UTF_8);
        int rangeStart = lineStart(content, args.startLine());
        int rangeEnd = lineEnd(content, args.endLine());
        if (rangeStart < 0 || rangeEnd < rangeStart) {
            return "{\"status\":\"error\",\"error\":\"Line range outside file\"}";
        }
        int idx = content.indexOf(args.targetContent(), rangeStart);
        if (idx < 0 || idx + args.targetContent().length() > rangeEnd) {
            return "{\"status\":\"error\",\"error\":\"targetContent not found in selected range.\"}";
        }
        int next =
                content.indexOf(
                        args.targetContent(), idx + Math.max(1, args.targetContent().length()));
        if (next >= 0 && next + args.targetContent().length() <= rangeEnd) {
            return "{\"status\":\"error\",\"error\":\"targetContent is not unique in selected range.\"}";
        }
        String updated =
                content.substring(0, idx)
                        + args.replacementContent()
                        + content.substring(idx + args.targetContent().length());
        AtomicFileWrites.write(path, updated.getBytes(StandardCharsets.UTF_8), true);
        return "{\"status\":\"ok\",\"file\":\"" + args.absolutePath() + "\"}";
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
