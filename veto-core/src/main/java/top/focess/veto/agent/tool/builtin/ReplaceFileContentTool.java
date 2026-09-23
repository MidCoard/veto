package top.focess.veto.agent.tool.builtin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.capability.WorkspaceWriteCapability;
import top.focess.veto.agent.tool.ToolErrorCode;
import top.focess.veto.agent.tool.ToolErrors;
import top.focess.veto.agent.tool.ToolJson;
import top.focess.veto.agent.tool.WorkspaceWriteTool;
import top.focess.veto.api.agent.screening.Danger;
import top.focess.veto.api.agent.tool.Doc;
import top.focess.veto.api.agent.tool.ParamCategory;
import top.focess.veto.api.agent.tool.Required;
import top.focess.veto.api.agent.tool.SecurityHint;
import top.focess.veto.api.agent.tool.ToolCapability;
import top.focess.veto.api.agent.tool.ToolDoc;
import top.focess.veto.api.agent.tool.ToolDocs;
import top.focess.veto.api.agent.tool.ToolResultFormat;
import top.focess.veto.api.agent.tool.ToolSecurity;

/** {@code replace_file_content} — replace a single contiguous block of text in an existing file. */
@Component
@ToolSecurity(capability = ToolCapability.WORKSPACE_WRITE, defaultDanger = Danger.ELEVATED)
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
                    `Not a regular file: <absolutePath>` (`NOT_A_FILE`), \
                    `File too large: the file exceeds 16 MiB (16,777,216 bytes).` (`FILE_TOO_LARGE`), \
                    `Invalid arguments: startLine must be at least 1 and endLine must be at least \
                    startLine.`, `Invalid arguments: the line range is outside the file.`, or \
                    `Invalid arguments: targetContent must not be empty.` (`INVALID_ARGUMENTS`) or \
                    `File too large: the replacement exceeds 16 MiB (16,777,216 bytes).` \
                    (`FILE_TOO_LARGE`).
                    - Match failure (failure): \
                    `Target not found: the selected range does not contain targetContent.` \
                    (`TARGET_NOT_FOUND`) or \
                    `Target not unique: the selected range contains targetContent more than once.` \
                    (`TARGET_NOT_UNIQUE`). \
                    The file remains unchanged.
                    - Update failure (failure, `IO_ERROR`): \
                    `I/O error: cannot update file <absolutePath>.`
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
                    - Replacing the directory entry can replace filesystem metadata. Symbolic-link and \
                    Windows reparse-point targets are rejected rather than followed or replaced.
                    """,
        security =
                "The matched block is replaced in place, so an unintended unique match overwrites the wrong text; quote enough context to pin the target. A failed match leaves the file unchanged.",
        examples = {
            "{\"absolutePath\": \"/abs/project/src/Main.java\", \"startLine\": 12, \"endLine\": 12, \"targetContent\": \"int x = 1;\", \"replacementContent\": \"int x = 2;\"}",
            "{\"absolutePath\": \"/abs/project/src/Main.java\", \"startLine\": 5, \"endLine\": 8, \"targetContent\": \"    void run() {\\n        start();\\n    }\", \"replacementContent\": \"    void run() {\\n        prepare();\\n        start();\\n    }\"}",
            "{\"absolutePath\": \"/abs/project/src/Main.java\", \"startLine\": 20, \"endLine\": 22, \"targetContent\": \"    // TODO: drop debug logging\\n    log.debug(\\\"state\\\");\\n\", \"replacementContent\": \"\"}",
            "{\"absolutePath\": \"/abs/project/src/Service.java\", \"startLine\": 40, \"endLine\": 46, \"targetContent\": \"    @Override\\n    public String name() {\\n        return \\\"legacy\\\";\\n    }\", \"replacementContent\": \"    @Override\\n    public String name() {\\n        return \\\"modern\\\";\\n    }\"}",
            "{\"absolutePath\": \"/abs/project/src/Main.java\", \"startLine\": 1, \"endLine\": 5, \"targetContent\": \"this text does not exist anywhere\", \"replacementContent\": \"x\"}"
        },
        returnExamples = {
            "{\"status\":\"ok\",\"file\":\"/abs/project/src/Main.java\"}",
            "{\"status\":\"ok\",\"file\":\"/abs/project/src/Main.java\"}",
            "{\"status\":\"ok\",\"file\":\"/abs/project/src/Main.java\"}",
            "{\"status\":\"ok\",\"file\":\"/abs/project/src/Service.java\"}",
            "Target not found: the selected range does not contain targetContent."
        })
public final class ReplaceFileContentTool
        implements WorkspaceWriteTool<ReplaceFileContentTool.Args> {
    private static final int MAX_TEXT_BYTES = 16 * 1024 * 1024;

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
    public @NonNull Class<Args> getArgsClass() {
        return ToolDocs.nonNullClass(Args.class);
    }

    @Override
    public @NonNull String execute(
            @NonNull Args args, @NonNull WorkspaceWriteCapability workspace) {
        if (args.startLine() < 1 || args.endLine() < args.startLine()) {
            return ToolErrors.failure(
                    ToolErrorCode.VALIDATION.INVALID_ARGUMENTS,
                    "Invalid arguments: startLine must be at least 1 and endLine must be at least"
                            + " startLine.");
        }
        if (args.targetContent().isEmpty())
            return ToolErrors.failure(
                    ToolErrorCode.VALIDATION.INVALID_ARGUMENTS,
                    "Invalid arguments: targetContent must not be empty.");
        try {
            var file = workspace.file(args.absolutePath());
            if (!"file".equals(file.kind()))
                return ToolErrors.failure(
                        ToolErrorCode.WORKSPACE.NOT_A_FILE,
                        "Not a regular file: " + args.absolutePath());
            if (file.size() > MAX_TEXT_BYTES)
                return ToolErrors.failure(
                        ToolErrorCode.WORKSPACE.FILE_TOO_LARGE,
                        "File too large: the file exceeds 16 MiB (16,777,216 bytes).");
            String content;
            try (var input = file.openRead()) {
                byte[] bytes = input.readNBytes(MAX_TEXT_BYTES + 1);
                if (bytes.length > MAX_TEXT_BYTES)
                    return ToolErrors.failure(
                            ToolErrorCode.WORKSPACE.FILE_TOO_LARGE,
                            "File too large: the file exceeds 16 MiB (16,777,216 bytes).");
                content = new String(bytes, StandardCharsets.UTF_8);
            }
            int start = lineStart(content, args.startLine());
            int end = lineEnd(content, args.endLine());
            if (start < 0 || end < start)
                return ToolErrors.failure(
                        ToolErrorCode.VALIDATION.INVALID_ARGUMENTS,
                        "Invalid arguments: the line range is outside the file.");
            int index = content.indexOf(args.targetContent(), start);
            if (index < 0 || index + args.targetContent().length() > end)
                return ToolErrors.failure(
                        ToolErrorCode.WORKSPACE.TARGET_NOT_FOUND,
                        "Target not found: the selected range does not contain targetContent.");
            int next = content.indexOf(args.targetContent(), index + 1);
            if (next >= 0 && next + args.targetContent().length() <= end)
                return ToolErrors.failure(
                        ToolErrorCode.WORKSPACE.TARGET_NOT_UNIQUE,
                        "Target not unique: the selected range contains targetContent more than"
                                + " once.");
            String updated =
                    content.substring(0, index)
                            + args.replacementContent()
                            + content.substring(index + args.targetContent().length());
            byte[] bytes = updated.getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_TEXT_BYTES)
                return ToolErrors.failure(
                        ToolErrorCode.WORKSPACE.FILE_TOO_LARGE,
                        "File too large: the replacement exceeds 16 MiB (16,777,216 bytes).");
            try (var output = file.openForReplace()) {
                output.write(bytes);
            }
            return ToolJson.object(new Result("ok", args.absolutePath()));
        } catch (NoSuchFileException e) {
            return ToolErrors.failure(
                    ToolErrorCode.WORKSPACE.NOT_A_FILE,
                    "Not a regular file: " + args.absolutePath());
        } catch (IOException e) {
            return ToolErrors.failure(
                    ToolErrorCode.WORKSPACE.IO_ERROR,
                    "I/O error: cannot update file " + args.absolutePath() + ".");
        }
    }

    private static int lineStart(@NonNull String content, int number) {
        if (number == 1) return 0;
        int current = 1;
        for (int i = 0; i < content.length(); i++)
            if (content.charAt(i) == '\n' && ++current == number) return i + 1;
        return -1;
    }

    private static int lineEnd(@NonNull String content, int number) {
        int start = lineStart(content, number);
        if (start < 0) return -1;
        int newline = content.indexOf('\n', start);
        return newline < 0 ? content.length() : newline + 1;
    }

    public record Result(@NonNull String status, @NonNull String file) {}
}
