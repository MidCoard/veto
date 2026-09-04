package top.focess.veto.agent.mcp.tools;

import java.util.List;
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

/** Searches file contents through a call-scoped workspace-read capability. */
@Component
@ToolSecurity(capability = ToolCapability.WORKSPACE_READ, defaultDanger = Danger.SAFE)
public final class GrepSearchTool implements WorkspaceReadTool<GrepSearchTool.Args> {

    @ToolDoc(
            resultFormats = {ToolResultFormat.PLAINTEXT},
            description = "Search for exact pattern matches inside files.",
            behavior =
                    """
                    Walks `absolutePath` recursively and reports each UTF-8 line that contains `query` as a \
                    substring. When `caseInsensitive` is true, casing in \
                    either the query or line is ignored. `includes`, when given, restricts the search to files whose \
                    root-relative path or basename matches one of the glob filters.

                    Files that cannot be opened or decoded completely as UTF-8 are skipped without aborting the whole \
                    search. Symbolic links and Windows reparse points are never followed. Every descendant is checked \
                    against the authorized root and protected paths before it is opened. At most 10000 files, 2000 \
                    matches, and 1000000 output characters are processed, with a maximum traversal time of 10 seconds; \
                    a truncation marker means the result is incomplete.
                    """,
            whenToUse =
                    """
                    Use `grep_search` to locate occurrences of an exact text pattern across a tree of files - finding \
                    where a symbol is referenced, tracking down a `TODO`/`FIXME` marker, finding a definition, or \
                    enumerating call sites before a refactor. Prefer it over `view_file` when you do not yet know which \
                    file holds the text; grep identifies the file and line, then `view_file` reads surrounding context.
                    """,
            whenNotToUse =
                    """
                    - Do not use `grep_search` to read a file whose path you already know - use `view_file`.
                    - Do not use it to list a directory - use `list_dir`.
                    - The match is an exact substring only. There is no regex, alternation, or anchoring.
                    """,
            resultContract =
                    """
                    - Success: one match per line as `<file>:<lineNumber>: <line text>` (1-indexed). No hits returns \
                    `(no matches)`; bounded results end with `[truncated: ...]`.
                    - Missing path (failure, PATH_NOT_FOUND): `Search path does not exist: <absolutePath>`.
                    - Empty query (failure, INVALID_QUERY): `query must not be empty`.
                    - Invalid include glob (failure, INVALID_PATTERN): `Invalid includes glob`.
                    - A symbolic-link or reparse-point root fails with UNSAFE_LINK; a protected root fails with \
                    PATH_PROTECTED.
                    """,
            errorsAndEdgeCases =
                    """
                    - `absolutePath` may name one regular file or a directory tree.
                    - Very large trees are truncated; narrow with `includes` or a tighter `absolutePath`.
                    - Unreadable, changing, linked, protected, and non-UTF-8 files are skipped.
                    - `caseInsensitive` and `includes` are optional; omit them for a case-sensitive search of all files.
                    """,
            security =
                    """
                    The Gateway screens `absolutePath`, then issues a call-scoped WorkspaceRead capability. The tool \
                    cannot open raw filesystem paths itself. The capability enforces the authorized root, protected \
                    paths, no-follow traversal, and resource bounds before file content is read.
                    """,
            examples = {
                "{\"absolutePath\": \"/abs/src\", \"query\": \"TODO\"}",
                "{\"absolutePath\": \"/abs/src\", \"query\": \"todo\", \"caseInsensitive\": true}",
                "{\"absolutePath\": \"/abs/src\", \"query\": \"public class \", \"includes\": [\"*.java\"]}"
            },
            returnExamples = {
                "/abs/src/Main.java:12: // TODO: refactor\n/abs/src/util/Helper.java:30: // TODO(jess): cleanup",
                "(no matches)"
            })
    public record Args(
            @SecurityHint(ParamCategory.FILESYSTEM_PATH) @Doc("Absolute path to search under.")
                    @NonNull String absolutePath,
            @Doc("The exact pattern to match.") @NonNull String query,
            @Doc("Whether to match case-insensitively.") Boolean caseInsensitive,
            @Doc("Glob filters for which files to include.") List<String> includes) {}

    @Override
    public @NonNull String getName() {
        return "grep_search";
    }

    @Override
    public @NonNull String getDescription() {
        return "Search for exact pattern matches inside files.";
    }

    @Override
    public @NonNull Class<Args> getArgsClass() {
        return ToolDocs.nonNullClass(Args.class);
    }

    @Override
    public @NonNull String execute(
            @NonNull Args args, @NonNull WorkspaceReadCapability capability) {
        return capability.grep(
                "absolutePath",
                args.query(),
                Boolean.TRUE.equals(args.caseInsensitive()),
                args.includes());
    }
}
