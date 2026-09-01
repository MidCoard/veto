package top.focess.veto.agent.mcp.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
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
import top.focess.veto.util.Nullness;

/** {@code list_dir} — list contents of a directory (files and child subdirectories). */
@Component
@ToolSecurity(risk = RiskCategory.READ_ONLY, capability = ToolCapability.WORKSPACE_READ)
public final class ListDirTool implements NativeTool<ListDirTool.Args> {

    private static final int MAX_ENTRIES = 5000;

    @ToolDoc(
            resultFormats = {ToolResultFormat.PLAINTEXT},
            description = "List contents of a directory (files and child subdirectories).",
            behavior =
                    """
                    Lists the direct children of `absolutePath` (files and subdirectories, one level deep). \
                    Entries are sorted lexicographically. Subdirectory names are suffixed with `/` so you can \
                    distinguish folders from files at a glance. Hidden files (dotfiles) are included. The \
                    listing is not recursive.
                    """,
            whenToUse =
                    """
                    Use `list_dir` to discover the immediate contents of a directory - enumerating a project's \
                    top-level layout, finding what files a module contains, or locating a subdirectory before \
                    reading a specific file. It returns the names of files and child directories in the given \
                    directory (one level deep, non-recursive).

                    It is the right first step when you know a directory but not its contents.
                    """,
            whenNotToUse =
                    """
                    - Do not use `list_dir` to read a file's contents - use `view_file`.
                    - Do not use it to search for text across files - use `grep_search`.
                    - Do not use it expecting a recursive tree; it lists one level only. To descend, call \
                    `list_dir` on each child directory you care about.
                    - Do not use it to check whether a single specific file exists; `view_file` on that path \
                    tells you directly.
                    """,
            resultContract =
                    """
                    - Success: one sorted entry per line. Directory \
                    entries end with `/`; file entries do not. An empty directory yields no lines.
                    - Supplied `absolutePath` does not exist or is not a directory (failure): \
                    `Not a directory: <absolutePath>`.
                    - Directory cannot be opened or enumerated (failure): \
                    `Cannot list directory: <absolutePath>`.
                    """,
            errorsAndEdgeCases =
                    """
                    - After a path rejection, do not retry a similar guess. Return to the last \
                    successful parent listing and reconstruct the path from observed child names. A common \
                    mistake is dropping a parent segment. If the intended target is a file, use `view_file`.
                    - At most 5000 entries are returned. A truncation marker means the directory must be
                    narrowed before relying on the listing as complete.
                    - A directory access or iteration failure rejects the listing; it is not returned as a \
                    partial success.
                    """,
            security =
                    """
                    `absolutePath` is a FILESYSTEM_PATH parameter: the Gateway screens it against the deployer \
                    policy and allowed roots before the listing. The operation is read-only \
                    (`RiskCategory.READ_ONLY`); nothing is modified. Returned names are subject to ingress \
                    masking. Do not attempt to list deployer-fenced roots - the call is blocked upstream; \
                    change scope instead.
                    """,
            examples = {
                "{\"absolutePath\": \"/abs/src\"}",
                "{\"absolutePath\": \"/abs\"}",
                "{\"absolutePath\": \"/abs/src/main/java\"}",
                "{\"absolutePath\": \"/abs/src/test\"}",
                "{\"absolutePath\": \"/abs/config\"}",
                "{\"absolutePath\": \"/abs/src/util\"}",
                "{\"absolutePath\": \"/abs/notes\"}"
            },
            returnExamples = {"README.md\nbuild.gradle.kts\nsrc/"})
    public record Args(
            @SecurityHint(ParamCategory.FILESYSTEM_PATH) @Doc("Absolute path to list contents of.")
                    @NonNull String absolutePath) {}

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
        return ToolDocs.nonNullClass(Args.class);
    }

    @Override
    public @NonNull String execute(@NonNull Args args) throws IOException {
        Path path = Path.of(args.absolutePath());
        if (!Files.isDirectory(path)) {
            return ToolErrors.failure("Not a directory: " + args.absolutePath());
        }
        StringBuilder sb = new StringBuilder();
        List<Path> entries = new ArrayList<>(MAX_ENTRIES + 1);
        try {
            try (var stream = Files.list(path)) {
                var iterator = stream.iterator();
                while (iterator.hasNext() && entries.size() <= MAX_ENTRIES) {
                    entries.add(iterator.next());
                }
            }
        } catch (IOException | java.io.UncheckedIOException e) {
            return ToolErrors.failure("Cannot list directory: " + args.absolutePath());
        }
        boolean truncated = entries.size() > MAX_ENTRIES;
        if (truncated) {
            entries.remove(entries.size() - 1);
        }
        entries.sort(
                Comparator.comparing(
                        p ->
                                Nullness.requireNonNull(
                                                p.getFileName(), "Directory entry has no file name")
                                        .toString()));
        for (Path entry : entries) {
            sb.append(
                            Nullness.requireNonNull(
                                    entry.getFileName(), "Directory entry has no file name"))
                    .append(Files.isDirectory(entry) ? "/\n" : "\n");
        }
        if (truncated) {
            sb.append("[truncated at ").append(MAX_ENTRIES).append(" entries]\n");
        }
        return sb.toString();
    }
}
