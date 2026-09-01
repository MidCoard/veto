package top.focess.veto.agent.mcp.tools;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
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

/** {@code grep_search} — search for exact pattern matches inside files. */
@Component
@ToolSecurity(risk = RiskCategory.READ_ONLY, capability = ToolCapability.WORKSPACE_READ)
public final class GrepSearchTool implements NativeTool<GrepSearchTool.Args> {

    private static final int MAX_FILES = 10_000;
    private static final int MAX_MATCHES = 2_000;
    private static final int MAX_OUTPUT_CHARS = 1_000_000;

    @ToolDoc(
            resultFormats = {ToolResultFormat.PLAINTEXT},
            description = "Search for exact pattern matches inside files.",
            usage =
                    """
                    #### When to use
                    Use `grep_search` to locate occurrences of an exact text pattern across a tree of files \
                    - finding where a symbol is referenced, tracking down a `TODO`/`FIXME` marker, finding the \
                    definition site of a function, or enumerating every call site before a refactor. It is the \
                    primary tool for "where is X mentioned in the codebase?".

                    Prefer it over `view_file` when you do not yet know which file holds the text: grep tells you \
                    the file and line, `view_file` then reads the surrounding context.

                    #### When NOT to use
                    - Do not use `grep_search` to read a file whose path you already know - use `view_file` instead \
                    (it returns line numbers and full surrounding context, which grep does not).
                    - Do not use it to list what is in a directory - use `list_dir`.
                    - Do not use it for whole-file inspection or to follow an import chain.
                    - The match is an exact substring only. There is no regex, no alternation, no anchoring - do \
                    not reach for it expecting `.*` or `^foo`; refine your literal pattern instead.

                    #### Behavior
                    Walks `absolutePath` recursively, opens every regular file as UTF-8, and reports each line that \
                    contains `query` as a substring. Matching is byte-exact against the decoded text. When \
                    `caseInsensitive` is true, both the query and each line are lowercased before the substring \
                    test, so casing in either is ignored. `includes`, when given, restricts the walk to files \
                    whose root-relative path or basename matches one of the glob filters.

                    Files that cannot be read completely as UTF-8 are skipped without aborting the whole search. \
                    Directory symbolic links are not followed. Regular-file symbolic links are read like their \
                    targets, subject to the authorized filesystem boundary. At most 10000 files, 2000 matches, \
                    and 1000000 output characters are processed; a truncation marker means the result is incomplete.

                    #### Return format
                    - Success: one match per line as \
                    `<file>:<lineNumber>: <line text>` (1-indexed). No hits returns `(no matches)`; \
                    bounded results end with `[truncated: ...]`.
                    - Supplied target does not exist (failure): \
                    `Path not found: <path>`.
                    - Invalid `includes` glob (failure): \
                    `Invalid includes glob`.

                    #### Errors & edge cases
                    - `absolutePath` is a file rather than a directory -> it is still walked; that single file is \
                    searched.
                    - An empty `query` matches every line of every file (the empty substring is in every string) - \
                    avoid passing an empty query.
                    - Very large trees are truncated; narrow with `includes` or scope `absolutePath` tighter.
                    - A traversal/read failure can make the result incomplete even when no truncation marker is \
                    available; retry with a narrower path when completeness matters.
                    - `caseInsensitive` and `includes` are optional; omit them for a plain case-sensitive search \
                    of all files.

                    #### Security
                    `absolutePath` is a FILESYSTEM_PATH parameter: the Gateway screens it against the deployer \
                    policy and allowed roots before the walk begins. The operation is read-only \
                    (`RiskCategory.READ_ONLY`); no file is modified. Matched content flows back as tool output \
                    and is subject to ingress masking. Do not attempt to search roots the deployer has fenced \
                    off - the call is blocked upstream; change scope instead.
                    """,
            examples = {
                "{\"absolutePath\": \"/abs/src\", \"query\": \"TODO\"}",
                "{\"absolutePath\": \"/abs/src\", \"query\": \"todo\", \"caseInsensitive\": true}",
                "{\"absolutePath\": \"/abs/src\", \"query\": \"public class \", \"includes\": [\"*.java\"]}",
                "{\"absolutePath\": \"/abs/src/Main.java\", \"query\": \"import \"}",
                "{\"absolutePath\": \"/abs\", \"query\": \"FIXME\", \"caseInsensitive\": true, \"includes\": [\"*.ts\", \"*.tsx\"]}",
                "{\"absolutePath\": \"/abs/config\", \"query\": \"password\"}",
                "{\"absolutePath\": \"/abs/src\", \"query\": \"@Override\"}",
                "{\"absolutePath\": \"/abs\", \"query\": \"TODO(jess)\"}"
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
    public @NonNull String execute(@NonNull Args args) throws IOException {
        Path root = Path.of(args.absolutePath());
        if (!Files.exists(root)) {
            return ToolErrors.failure("Path not found: " + args.absolutePath());
        }
        boolean ci = Boolean.TRUE.equals(args.caseInsensitive());
        String query = ci ? args.query().toLowerCase(Locale.ROOT) : args.query();
        List<PathMatcher> includes;
        try {
            includes = compileIncludes(args.includes());
        } catch (IllegalArgumentException e) {
            return ToolErrors.failure("Invalid includes glob");
        }
        StringBuilder sb = new StringBuilder();
        int visitedFiles = 0;
        int matches = 0;
        String truncationReason = null;
        try (Stream<Path> files = Files.walk(root)) {
            var iterator = files.filter(Files::isRegularFile).iterator();
            search:
            while (iterator.hasNext()) {
                Path file = iterator.next();
                if (!matchesIncludes(root, file, includes)) {
                    continue;
                }
                if (visitedFiles >= MAX_FILES) {
                    truncationReason = "file limit " + MAX_FILES;
                    break;
                }
                visitedFiles++;
                try (Stream<String> lines = Files.lines(file, StandardCharsets.UTF_8)) {
                    var lineIterator = lines.iterator();
                    int lineNumber = 0;
                    while (lineIterator.hasNext()) {
                        String line = lineIterator.next();
                        lineNumber++;
                        String candidate = ci ? line.toLowerCase(Locale.ROOT) : line;
                        if (!candidate.contains(query)) {
                            continue;
                        }
                        String rendered = file + ":" + lineNumber + ": " + line + "\n";
                        if (matches >= MAX_MATCHES
                                || sb.length() + rendered.length() > MAX_OUTPUT_CHARS) {
                            truncationReason =
                                    matches >= MAX_MATCHES
                                            ? "match limit " + MAX_MATCHES
                                            : "output limit " + MAX_OUTPUT_CHARS + " chars";
                            break search;
                        }
                        sb.append(rendered);
                        matches++;
                    }
                } catch (IOException | UncheckedIOException ignored) {
                    // Unreadable or non-text files do not abort the whole search.
                }
            }
        } catch (UncheckedIOException ignored) {
            // A file can become unreadable while Files.walk is consumed. Keep prior matches.
        }
        if (truncationReason != null) {
            sb.append("[truncated: ").append(truncationReason).append("]\n");
        }
        return sb.isEmpty() ? "(no matches)" : sb.toString();
    }

    private static @NonNull List<PathMatcher> compileIncludes(List<String> patterns) {
        if (patterns == null || patterns.isEmpty()) {
            return List.of();
        }
        List<PathMatcher> matchers = new ArrayList<>();
        for (String pattern : patterns) {
            if (pattern != null && !pattern.isBlank()) {
                matchers.add(FileSystems.getDefault().getPathMatcher("glob:" + pattern));
            }
        }
        return List.copyOf(matchers);
    }

    private static boolean matchesIncludes(
            @NonNull Path root, @NonNull Path file, @NonNull List<PathMatcher> includes) {
        if (includes.isEmpty()) {
            return true;
        }
        Path relative =
                Nullness.requireNonNull(
                        Files.isDirectory(root) ? root.relativize(file) : file.getFileName(),
                        "Search file has no relative name");
        Path fileName = Nullness.requireNonNull(file.getFileName(), "Search file has no file name");
        return includes.stream()
                .anyMatch(matcher -> matcher.matches(relative) || matcher.matches(fileName));
    }
}
