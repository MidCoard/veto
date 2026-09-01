package top.focess.veto.agent.mcp.tools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
import top.focess.veto.agent.mcp.ToolResultFormat;
import top.focess.veto.agent.mcp.ToolSecurity;

/** {@code view_file} — read lines of a text file from the local filesystem. */
@Component
@ToolSecurity(risk = RiskCategory.READ_ONLY, capability = ToolCapability.WORKSPACE_READ)
public final class ViewFileTool implements NativeTool<ViewFileTool.Args> {

    private static final long MAX_FILE_BYTES = 16L * 1024L * 1024L;
    private static final int MAX_OUTPUT_LINES = 5000;
    private static final int MAX_OUTPUT_CHARS = 1_000_000;

    /** Parameter container for {@code view_file}. */
    @ToolDoc(
            resultFormats = {ToolResultFormat.PLAINTEXT},
            description = "Read lines of a text file from the local filesystem.",
            usage =
                    """
                    #### When to use
                    Use `view_file` to read the contents of a text file from the local filesystem - to inspect \
                    source before editing, understand a module's structure, read a config file, or check the \
                    current state of a file you plan to patch. It returns lines prefixed with their 1-indexed \
                    line numbers, which you can quote back when composing a `replace_file_content` call.

                    It is the read counterpart to `write_to_file` / `replace_file_content`. Always read before \
                    you write.

                    #### When NOT to use
                    - Do not use `view_file` to search for a pattern across many files - use `grep_search`.
                    - Do not use it to discover what files exist - use `list_dir`.
                    - Do not use it on binary or non-text files; it decodes as UTF-8 and large binaries will \
                    flood your context.
                    - Do not use it to create or modify a file - it is strictly read-only.

                    #### Behavior
                    Reads the file at `absolutePath` as UTF-8 and returns the requested line range. `startLine` \
                    and `endLine` are 1-indexed and inclusive. When `startLine` is omitted, reading starts at \
                    line 1; when `endLine` is omitted, it runs to the last line. Ranges are clamped: `startLine` \
                    is floored at 1, `endLine` is capped at the file's line count. Passing neither returns the \
                    whole file.

                    #### Return format
                    - Success: one output line per source line as \
                    `<lineNumber>: <line text>` (1-indexed). An empty range yields no lines.
                    - Missing or non-regular path (failure): \
                    `Not a regular file: <path>`.
                    - Oversized file (failure): \
                    `File exceeds 16777216 bytes; request a smaller artifact`.

                    #### Errors & edge cases
                    - `absolutePath` does not exist or is not a regular file -> \
                    `Not a regular file: <path>` as a failed result. **This is the canonical
                    signal that the path you constructed does not exist as a file.** The right response
                    is NOT to retry with a similar guess - return to your last successful `list_dir`
                    observation of the parent directory and reconstruct the absolute path from the
                    actual file names you saw there. The most common cause is dropping or duplicating
                    a parent segment.
                    - `startLine` greater than the file length -> no output (range clamped to empty).
                    - `endLine` less than `startLine` -> no output.
                    - Directories, device files, and sockets are rejected as "not a regular file".
                    - Very large files: scope with `startLine`/`endLine` rather than reading the whole file.

                    #### Security
                    `absolutePath` is a FILESYSTEM_PATH parameter: the Gateway screens it against the deployer \
                    policy and allowed roots before the read. The operation is read-only \
                    (`RiskCategory.READ_ONLY`); the file is never modified. Returned content is subject to \
                    ingress masking. Do not attempt to read deployer-fenced material (`application.yml`, \
                    `audit/`, etc. under non-FULL_ACCESS policies) - the call is blocked upstream; change \
                    approach instead.
                    """,
            examples = {
                "{\"absolutePath\": \"/abs/src/Main.java\"}",
                "{\"absolutePath\": \"/abs/src/Main.java\", \"startLine\": 10, \"endLine\": 20}",
                "{\"absolutePath\": \"/abs/README.md\"}",
                "{\"absolutePath\": \"/abs/src/Main.java\", \"startLine\": 1, \"endLine\": 50}",
                "{\"absolutePath\": \"/abs/build.gradle.kts\"}",
                "{\"absolutePath\": \"/abs/src/Main.java\", \"startLine\": 100}",
                "{\"absolutePath\": \"/abs/config/app.yml\", \"endLine\": 30}"
            },
            returnExamples = {"1: package com.example;\n2: \n3: public class Main {"})
    public record Args(
            @SecurityHint(ParamCategory.FILESYSTEM_PATH)
                    @Doc("The absolute path of the file to view.")
                    @NonNull String absolutePath,
            @Doc("1-indexed starting line (inclusive).") Integer startLine,
            @Doc("1-indexed ending line (inclusive).") Integer endLine) {}

    @Override
    public @NonNull String getName() {
        return "view_file";
    }

    @Override
    public @NonNull String getDescription() {
        return "Read lines of a text file from the local filesystem.";
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
        if (Files.size(path) > MAX_FILE_BYTES) {
            return ToolErrors.failure("File exceeds 16777216 bytes; request a smaller artifact");
        }
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        int from = args.startLine() == null ? 1 : Math.max(1, args.startLine());
        int to = args.endLine() == null ? lines.size() : Math.min(lines.size(), args.endLine());
        StringBuilder sb = new StringBuilder();
        boolean truncated = false;
        int emitted = 0;
        for (int i = from; i <= to; i++) {
            String rendered = i + ": " + lines.get(i - 1) + "\n";
            if (emitted >= MAX_OUTPUT_LINES || sb.length() + rendered.length() > MAX_OUTPUT_CHARS) {
                truncated = true;
                break;
            }
            sb.append(rendered);
            emitted++;
        }
        if (truncated) {
            sb.append("[truncated; request a narrower line range]\n");
        }
        return sb.toString();
    }
}
