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

/** {@code replace_file_content} — replace a single contiguous block of text in an existing file. */
@Component
@ToolSecurity(risk = RiskCategory.FILE_WRITE)
public final class ReplaceFileContentTool implements NativeTool<ReplaceFileContentTool.Args> {

    @ToolDoc(
            description = "Replace a single contiguous block of code in an existing file.",
            usage =
                    """
                    #### When to use
                    Use `replace_file_content` to make a localized, surgical edit to an existing file - renaming \
                    a symbol in one spot, fixing a few lines, or swapping a block for new text. It targets a \
                    contiguous range and replaces the first exact occurrence of `targetContent` with \
                    `replacementContent`. Prefer it over `write_to_file` when most of the file is unchanged.

                    Always `view_file` the target range first so your `targetContent` matches the file exactly \
                    (whitespace included).

                    #### When NOT to use
                    - Do not use `replace_file_content` to create a file or rewrite most of it - use \
                    `write_to_file`.
                    - Do not use it blind - if `targetContent` does not match the file byte-for-byte, the call \
                    fails. Read first.
                    - Do not use it to replace ALL occurrences; only the first match is replaced. For multiple \
                    sites, call it repeatedly or use `write_to_file`.
                    - Do not pass a `targetContent` so short it could match unintended locations (e.g. a bare \
                    `}`); include enough surrounding context to be unique.

                    #### Behavior
                    Reads `targetFile` as UTF-8, locates the first occurrence of `targetContent` as an exact \
                    substring, and replaces that span with `replacementContent`, preserving everything before \
                    and after. `startLine`/`endLine` are informational scoping hints (1-indexed, inclusive) \
                    carried for audit/context; the match is performed on the full file content, not \
                    line-range-truncated. The file is then rewritten.

                    #### Return format
                    On success: `{"status":"ok","file":"<path>"}`. On failure to find the target: \
                    `{"status":"error","error":"targetContent not found in file."}`. On a non-file path: \
                    `{"status":"error","error":"Not a regular file: <path>"}`.

                    #### Errors & edge cases
                    - `targetContent` not present -> error status; the file is left untouched.
                    - `targetFile` is not a regular file -> error status.
                    - Only the FIRST occurrence is replaced; subsequent matches are left as-is.
                    - `targetContent` and `replacementContent` are exact (whitespace, indentation, newlines all \
                    matter). A mismatched indent means "not found".
                    - `startLine`/`endLine` do not restrict the search; they are context for the screen/audit, \
                    not a truncation window.

                    #### Security
                    `targetFile` is a FILESYSTEM_PATH; `targetContent` and `replacementContent` are CODE_CONTENT. \
                    The Gateway screens the path and applies semantic screening to the replacement content \
                    before the write. The operation is `RiskCategory.FILE_WRITE` (elevated + audited). Do not \
                    attempt to patch deployer-fenced paths or smuggle disallowed content - the call is blocked \
                    upstream; change approach instead.
                    """,
            examples = {
                "{\"targetFile\": \"/abs/src/Main.java\", \"startLine\": 5, \"endLine\": 8, \"targetContent\": \"old\", \"replacementContent\": \"new\"}",
                "{\"targetFile\": \"/abs/src/Main.java\", \"startLine\": 12, \"endLine\": 12, \"targetContent\": \"int x = 1;\", \"replacementContent\": \"int x = 2;\"}",
                "{\"targetFile\": \"/abs/src/Main.java\", \"startLine\": 1, \"endLine\": 1, \"targetContent\": \"package old;\", \"replacementContent\": \"package new;\"}",
                "{\"targetFile\": \"/abs/README.md\", \"startLine\": 3, \"endLine\": 3, \"targetContent\": \"# Old Title\", \"replacementContent\": \"# New Title\"}",
                "{\"targetFile\": \"/abs/config/app.yml\", \"startLine\": 10, \"endLine\": 10, \"targetContent\": \"port: 8080\", \"replacementContent\": \"port: 8443\"}",
                "{\"targetFile\": \"/abs/src/Main.java\", \"startLine\": 20, \"endLine\": 24, \"targetContent\": \"// TODO\\n\", \"replacementContent\": \"// done\\n\"}",
                "{\"targetFile\": \"/abs/src/Main.java\", \"startLine\": 8, \"endLine\": 8, \"targetContent\": \"    return null;\", \"replacementContent\": \"    return value;\"}"
            },
            returnExamples = {"{\"status\":\"ok\",\"file\":\"/abs/src/Main.java\"}"})
    public record Args(
            @SecurityHint(ParamCategory.FILESYSTEM_PATH) @Doc("Absolute path of the file to patch.")
                    @NonNull String targetFile,
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
        return Args.class;
    }

    @Override
    public @NonNull String execute(@NonNull Args args) throws IOException {
        Path path = Path.of(args.targetFile());
        if (!Files.isRegularFile(path)) {
            return "{\"status\":\"error\",\"error\":\"Not a regular file: "
                    + args.targetFile()
                    + "\"}";
        }
        String content = Files.readString(path, StandardCharsets.UTF_8);
        int idx = content.indexOf(args.targetContent());
        if (idx < 0) {
            return "{\"status\":\"error\",\"error\":\"targetContent not found in file.\"}";
        }
        String updated =
                content.substring(0, idx)
                        + args.replacementContent()
                        + content.substring(idx + args.targetContent().length());
        Files.writeString(path, updated, StandardCharsets.UTF_8);
        return "{\"status\":\"ok\",\"file\":\"" + args.targetFile() + "\"}";
    }
}
