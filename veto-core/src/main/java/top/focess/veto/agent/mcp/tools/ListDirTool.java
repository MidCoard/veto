package top.focess.veto.agent.mcp.tools;

import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.capability.WorkspaceReadCapability;
import top.focess.veto.agent.mcp.Doc;
import top.focess.veto.agent.mcp.ParamCategory;
import top.focess.veto.agent.mcp.SecurityHint;
import top.focess.veto.agent.mcp.ToolCapability;
import top.focess.veto.agent.mcp.ToolDoc;
import top.focess.veto.agent.mcp.ToolDocs;
import top.focess.veto.agent.mcp.ToolResultFormat;
import top.focess.veto.agent.mcp.ToolSecurity;
import top.focess.veto.agent.mcp.WorkspaceReadTool;
import top.focess.veto.agent.screening.Danger;

/** {@code list_dir} — list contents of a directory (files and child subdirectories). */
@Component
@ToolSecurity(capability = ToolCapability.WORKSPACE_READ, defaultDanger = Danger.SAFE)
public final class ListDirTool implements WorkspaceReadTool<ListDirTool.Args> {

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
                    `absolutePath` is a FILESYSTEM_PATH parameter: the Gateway canonicalizes it and screens it \
                    under the deployer policy before the listing. Under FULL_ACCESS, workspace roots are working \
                    context rather than a path boundary, so any absolute host path may be targeted; restrictive \
                    policies may fence paths. The operation is read-only \
                    (`WORKSPACE_READ`, default danger `SAFE`); nothing is modified. Returned names are subject to ingress \
                    masking. If the Gateway actually refuses a deployer-fenced path, change scope instead.
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
    public @NonNull String execute(
            @NonNull Args args, @NonNull WorkspaceReadCapability capability) {
        return capability.listDirectory("absolutePath");
    }
}
