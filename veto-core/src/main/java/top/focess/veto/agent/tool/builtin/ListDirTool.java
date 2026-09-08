package top.focess.veto.agent.tool.builtin;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.util.ArrayList;
import java.util.Comparator;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.capability.WorkspaceFile;
import top.focess.veto.agent.capability.WorkspaceReadCapability;
import top.focess.veto.agent.screening.Danger;
import top.focess.veto.agent.tool.Doc;
import top.focess.veto.agent.tool.ParamCategory;
import top.focess.veto.agent.tool.SecurityHint;
import top.focess.veto.agent.tool.ToolCapability;
import top.focess.veto.agent.tool.ToolDoc;
import top.focess.veto.agent.tool.ToolDocs;
import top.focess.veto.agent.tool.ToolErrors;
import top.focess.veto.agent.tool.ToolResultFormat;
import top.focess.veto.agent.tool.ToolSecurity;
import top.focess.veto.agent.tool.WorkspaceReadTool;

/** {@code list_dir} — list contents of a directory (files and child subdirectories). */
@Component
@ToolSecurity(capability = ToolCapability.WORKSPACE_READ, defaultDanger = Danger.SAFE)
@ToolDoc(
        resultFormats = {ToolResultFormat.PLAINTEXT},
        description = "List contents of a directory (files and child subdirectories).",
        behavior =
                """
                    Lists the direct children of `absolutePath` (files and subdirectories, one level deep). \
                    Entries are sorted lexicographically. Subdirectory names are suffixed with `/` so you can \
                    distinguish folders from files at a glance. Hidden files (dotfiles) are included. The \
                    listing is not recursive. Protected entries, symbolic links, and Windows reparse \
                    points are omitted, so an empty result means no visible entries.
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
                    entries end with `/`; file entries do not. A directory with no visible entries yields no lines.
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
                "Read-only. Follow the current Boundaries rules. If access is refused, change scope instead of retrying the same path.",
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
public final class ListDirTool implements WorkspaceReadTool<ListDirTool.Args> {

    public record Args(
            @SecurityHint(ParamCategory.FILESYSTEM_PATH) @Doc("Absolute path to list contents of.")
                    @NonNull String absolutePath) {}

    @Override
    public @NonNull String getName() {
        return "list_dir";
    }

    @Override
    public @NonNull Class<Args> getArgsClass() {
        return ToolDocs.nonNullClass(Args.class);
    }

    @Override
    public @NonNull String execute(@NonNull Args args, @NonNull WorkspaceReadCapability workspace) {
        try {
            WorkspaceFile directory = workspace.file(args.absolutePath());
            if (!directory.kind().equals("directory")) {
                return ToolErrors.failure(
                        "NOT_A_DIRECTORY", "Not a directory: " + args.absolutePath());
            }
            var entries = new ArrayList<String>();
            for (WorkspaceFile child : directory.children()) {
                String kind = child.kind();
                if (!kind.equals("file") && !kind.equals("directory")) continue;
                String name = WorkspaceTraversal.basename(child.name());
                entries.add(name + (kind.equals("directory") ? "/" : ""));
                if (entries.size() > 5000) break;
            }
            boolean truncated = entries.size() > 5000;
            if (truncated) entries.removeLast();
            entries.sort(
                    Comparator.comparing(
                            name ->
                                    name.endsWith("/")
                                            ? name.substring(0, name.length() - 1)
                                            : name));
            String output = entries.isEmpty() ? "" : String.join("\n", entries) + "\n";
            return truncated ? output + "[truncated at 5000 entries]\n" : output;
        } catch (NoSuchFileException e) {
            return ToolErrors.failure("NOT_A_DIRECTORY", "Not a directory: " + args.absolutePath());
        } catch (IOException e) {
            return ToolErrors.failure("IO_ERROR", "Cannot list directory: " + args.absolutePath());
        }
    }
}
