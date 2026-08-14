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
import top.focess.veto.agent.mcp.ToolDoc;
import top.focess.veto.agent.mcp.ToolDocs;
import top.focess.veto.agent.mcp.ToolSecurity;

/** {@code view_file} — read lines of a text file from the local filesystem. */
@Component
@ToolSecurity(risk = RiskCategory.READ_ONLY)
public final class ViewFileTool implements NativeTool<ViewFileTool.Args> {

    /** Parameter container for {@code view_file}. */
    @ToolDoc(
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
                    Plain text, one line per output line, in the form `<lineNumber>: <line text>` (1-indexed). \
                    There is no JSON envelope. An empty range yields no output lines.

                    #### Errors & edge cases
                    - `absolutePath` does not exist or is not a regular file -> \
                    `{"status":"error","error":"Not a regular file: <path>"}`. **This is the canonical
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
            return "{\"status\":\"error\",\"error\":\"Not a regular file: "
                    + args.absolutePath()
                    + "\"}";
        }
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        int from = args.startLine() == null ? 1 : Math.max(1, args.startLine());
        int to = args.endLine() == null ? lines.size() : Math.min(lines.size(), args.endLine());
        StringBuilder sb = new StringBuilder();
        for (int i = from; i <= to; i++) {
            sb.append(i).append(": ").append(lines.get(i - 1)).append('\n');
        }
        return sb.toString();
    }
}
