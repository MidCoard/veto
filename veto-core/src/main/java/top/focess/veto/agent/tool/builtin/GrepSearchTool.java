package top.focess.veto.agent.tool.builtin;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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

/** Searches file contents through a call-scoped workspace-read capability. */
@Component
@ToolSecurity(capability = ToolCapability.WORKSPACE_READ, defaultDanger = Danger.SAFE)
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
                "Read-only. Searches do not follow symbolic links or open protected files. Follow the current Boundaries rules.",
        examples = {
            "{\"absolutePath\": \"/abs/src\", \"query\": \"TODO\"}",
            "{\"absolutePath\": \"/abs/src\", \"query\": \"todo\", \"caseInsensitive\": true}",
            "{\"absolutePath\": \"/abs/src\", \"query\": \"public class \", \"includes\": [\"*.java\"]}"
        },
        returnExamples = {
            "/abs/src/Main.java:12: // TODO: refactor\n/abs/src/util/Helper.java:30: // TODO(jess): cleanup",
            "(no matches)"
        })
public final class GrepSearchTool implements WorkspaceReadTool<GrepSearchTool.Args> {

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
    public @NonNull Class<Args> getArgsClass() {
        return ToolDocs.nonNullClass(Args.class);
    }

    @Override
    public @NonNull String execute(@NonNull Args args, @NonNull WorkspaceReadCapability workspace) {
        if (args.query().isEmpty()) {
            return ToolErrors.failure("INVALID_QUERY", "query must not be empty");
        }
        List<PathMatcher> includes;
        try {
            includes = compileIncludes(args.includes());
        } catch (IllegalArgumentException e) {
            return ToolErrors.failure("INVALID_PATTERN", "Invalid includes glob");
        }
        boolean insensitive = Boolean.TRUE.equals(args.caseInsensitive());
        String query = insensitive ? args.query().toLowerCase(Locale.ROOT) : args.query();
        try {
            WorkspaceFile root = workspace.file(args.absolutePath());
            String kind = root.kind();
            if (kind.equals("missing")) {
                return ToolErrors.failure(
                        "PATH_NOT_FOUND", "Search path does not exist: " + args.absolutePath());
            }
            if (kind.equals("symbolic_link")) {
                return ToolErrors.failure(
                        "UNSAFE_LINK", "Search path is a symbolic link or reparse point");
            }
            var traversal = new WorkspaceTraversal(root);
            StringBuilder output = new StringBuilder();
            int files = 0;
            int matches = 0;
            String reason = null;
            WorkspaceFile file;
            search:
            while ((file = traversal.next()) != null) {
                String relative = traversal.relativeName();
                if (!includes.isEmpty()) {
                    Path relativePath = Path.of(relative);
                    Path basename = Path.of(WorkspaceTraversal.basename(file.name()));
                    if (includes.stream()
                            .noneMatch(m -> m.matches(relativePath) || m.matches(basename)))
                        continue;
                }
                if (files++ >= 10_000) {
                    reason = "file limit 10000";
                    break;
                }
                String display =
                        kind.equals("directory")
                                ? args.absolutePath()
                                        + (args.absolutePath().endsWith("/")
                                                        || args.absolutePath().endsWith("\\")
                                                ? ""
                                                : "/")
                                        + relative
                                : args.absolutePath();
                int previousLength = output.length();
                int previousMatches = matches;
                try (var reader =
                        new BufferedReader(
                                new InputStreamReader(
                                        file.openRead(), StandardCharsets.UTF_8.newDecoder()))) {
                    String line;
                    int lineNumber = 0;
                    while ((line = reader.readLine()) != null) {
                        if (!traversal.withinTime()) break search;
                        lineNumber++;
                        String candidate = insensitive ? line.toLowerCase(Locale.ROOT) : line;
                        if (!candidate.contains(query)) continue;
                        String rendered = display + ":" + lineNumber + ": " + line + "\n";
                        if (matches >= 2000 || output.length() + rendered.length() > 1_000_000) {
                            reason =
                                    matches >= 2000
                                            ? "match limit 2000"
                                            : "output limit 1000000 chars";
                            break search;
                        }
                        output.append(rendered);
                        matches++;
                    }
                } catch (IOException ignored) {
                    // Discard incomplete reads, including a later UTF-8 decoding failure.
                    output.setLength(previousLength);
                    matches = previousMatches;
                }
            }
            String boundaryReason = traversal.reason();
            if (reason == null && boundaryReason != null)
                reason = boundaryReason.toLowerCase(Locale.ROOT);
            if (reason != null) output.append("[truncated: ").append(reason).append("]\n");
            return output.isEmpty() ? "(no matches)" : output.toString();
        } catch (NoSuchFileException e) {
            return ToolErrors.failure(
                    "PATH_NOT_FOUND", "Search path does not exist: " + args.absolutePath());
        } catch (IOException e) {
            return ToolErrors.failure("IO_ERROR", "Cannot search path: " + args.absolutePath());
        }
    }

    private static @NonNull List<PathMatcher> compileIncludes(List<String> patterns) {
        if (patterns == null || patterns.isEmpty()) return List.of();
        List<PathMatcher> matchers = new ArrayList<>();
        for (String pattern : patterns) {
            if (pattern != null && !pattern.isBlank()) {
                matchers.add(FileSystems.getDefault().getPathMatcher("glob:" + pattern));
            }
        }
        return List.copyOf(matchers);
    }
}
