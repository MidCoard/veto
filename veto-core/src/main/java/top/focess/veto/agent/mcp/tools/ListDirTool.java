package top.focess.veto.agent.mcp.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.mcp.Doc;
import top.focess.veto.agent.mcp.NativeTool;
import top.focess.veto.agent.mcp.ParamCategory;
import top.focess.veto.agent.mcp.RiskCategory;
import top.focess.veto.agent.mcp.SecurityHint;
import top.focess.veto.agent.mcp.ToolDoc;
import top.focess.veto.agent.mcp.ToolSecurity;

/** {@code list_dir} — list contents of a directory (files and child subdirectories). */
@Component
@ToolSecurity(risk = RiskCategory.READ_ONLY)
public final class ListDirTool implements NativeTool<ListDirTool.Args> {

    @ToolDoc(
            description = "List contents of a directory (files and child subdirectories).",
            usage =
                    """
                    #### When to use
                    Use `list_dir` to discover the immediate contents of a directory - enumerating a project's \
                    top-level layout, finding what files a module contains, or locating a subdirectory before \
                    reading a specific file. It returns the names of files and child directories in the given \
                    directory (one level deep, non-recursive).

                    It is the right first step when you know a directory but not its contents.

                    #### When NOT to use
                    - Do not use `list_dir` to read a file's contents - use `view_file`.
                    - Do not use it to search for text across files - use `grep_search`.
                    - Do not use it expecting a recursive tree; it lists one level only. To descend, call \
                    `list_dir` on each child directory you care about.
                    - Do not use it to check whether a single specific file exists; `view_file` on that path \
                    tells you directly.

                    #### Behavior
                    Lists the direct children of `directoryPath` (files and subdirectories, one level deep). \
                    Entries are sorted lexicographically. Subdirectory names are suffixed with `/` so you can \
                    distinguish folders from files at a glance. Hidden files (dotfiles) are included. The \
                    listing is not recursive.

                    #### Return format
                    Plain text, one entry per line, sorted. Directory entries end with `/`; file entries do not. \
                    There is no JSON envelope. An empty directory yields no output lines.

                    #### Errors & edge cases
                    - `directoryPath` does not exist or is not a directory -> \
                    `{"status":"error","error":"Not a directory: <path>"}`. **This is the canonical
                    signal that the path you constructed does not exist.** The right response is NOT
                    to retry with a similar guess - return to your last successful `list_dir`
                    observation and reconstruct the absolute path from the actual subdirectory names
                    you saw there. The most common cause is dropping a parent segment (e.g. listing
                    `project/sub/config/` under `/abs/project`, then trying `/abs/project/config`
                    instead of `/abs/project/sub/config`).
                    - Passing a file path -> same "Not a directory" error; use `view_file` instead.
                    - A directory with many entries returns them all; there is no pagination - narrow by
                    descending into specific subdirectories if the listing is large.
                    - Permissions gaps may hide entries the process cannot read; the tool lists what the
                    filesystem exposes.

                    #### Security
                    `directoryPath` is a FILESYSTEM_PATH parameter: the Gateway screens it against the deployer \
                    policy and allowed roots before the listing. The operation is read-only \
                    (`RiskCategory.READ_ONLY`); nothing is modified. Returned names are subject to ingress \
                    masking. Do not attempt to list deployer-fenced roots - the call is blocked upstream; \
                    change scope instead.
                    """,
            examples = {
                "{\"directoryPath\": \"/abs/src\"}",
                "{\"directoryPath\": \"/abs\"}",
                "{\"directoryPath\": \"/abs/src/main/java\"}",
                "{\"directoryPath\": \"/abs/src/test\"}",
                "{\"directoryPath\": \"/abs/config\"}",
                "{\"directoryPath\": \"/abs/src/util\"}",
                "{\"directoryPath\": \"/abs/notes\"}"
            },
            returnExamples = {"src/\nbuild.gradle.kts\nREADME.md"})
    public record Args(
            @SecurityHint(ParamCategory.FILESYSTEM_PATH) @Doc("Absolute path to list contents of.")
                    @NonNull String directoryPath) {}

    @Override
    public @NonNull String getName() {
        return "list_dir";
    }

    @Override
    public @NonNull String getDescription() {
        return "List contents of a directory (files and child subdirectories).";
    }

    @Override
    public @NonNull Class<Args> getArgsClass() {
        return Args.class;
    }

    @Override
    public @NonNull String execute(@NonNull Args args) throws IOException {
        Path path = Path.of(args.directoryPath());
        if (!Files.isDirectory(path)) {
            return "{\"status\":\"error\",\"error\":\"Not a directory: "
                    + args.directoryPath()
                    + "\"}";
        }
        StringBuilder sb = new StringBuilder();
        try (Stream<Path> stream = Files.list(path)) {
            stream.sorted()
                    .forEach(
                            p ->
                                    sb.append(p.getFileName())
                                            .append(Files.isDirectory(p) ? "/\n" : "\n"));
        }
        return sb.toString();
    }
}
