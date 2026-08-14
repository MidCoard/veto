package top.focess.veto.agent.mcp.tools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
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

/** {@code grep_search} — search for exact pattern matches inside files. */
@Component
@ToolSecurity(risk = RiskCategory.READ_ONLY)
public final class GrepSearchTool implements NativeTool<GrepSearchTool.Args> {

    @ToolDoc(
            description = "Search for exact pattern matches inside files.",
            usage =
                    """
                    #### When to use
                    Use `grep_search` to locate every occurrence of an exact text pattern across a tree of files \
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
                    whose path matches one of the glob filters (matched against the full path).

                    Binary or non-UTF-8 files are skipped silently (their read errors are swallowed). `Files.walk` \
                    follows symlinks, so a cyclical link can produce repeated entries.

                    #### Return format
                    A plain-text report, one match per line, in the form `<file>:<lineNumber>: <line text>` \
                    (1-indexed line numbers). There is no JSON envelope, no count, and no truncation marker - an \
                    empty string means "no hits".

                    #### Errors & edge cases
                    - `absolutePath` does not exist -> `{"status":"error","error":"Path not found: <path>"}`.
                    - `absolutePath` is a file rather than a directory -> it is still walked; that single file is \
                    searched.
                    - An empty `query` matches every line of every file (the empty substring is in every string) - \
                    avoid passing an empty query.
                    - Very large trees can produce a large report; narrow with `includes` or scope `absolutePath` \
                    tighter first.
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
            return "{\"status\":\"error\",\"error\":\"Path not found: "
                    + args.absolutePath()
                    + "\"}";
        }
        boolean ci = Boolean.TRUE.equals(args.caseInsensitive());
        String query = ci ? args.query().toLowerCase() : args.query();
        StringBuilder sb = new StringBuilder();
        try (Stream<Path> files = Files.walk(root)) {
            files.filter(Files::isRegularFile)
                    .forEach(
                            file -> {
                                try (Stream<String> lines =
                                        Files.lines(file, StandardCharsets.UTF_8)) {
                                    List<String> all = lines.toList();
                                    for (int i = 0; i < all.size(); i++) {
                                        String line = all.get(i);
                                        String candidate = ci ? line.toLowerCase() : line;
                                        if (candidate.contains(query)) {
                                            sb.append(file)
                                                    .append(':')
                                                    .append(i + 1)
                                                    .append(": ")
                                                    .append(line)
                                                    .append('\n');
                                        }
                                    }
                                } catch (IOException ignored) {
                                }
                            });
        }
        return sb.isEmpty() ? "(no matches)" : sb.toString();
    }
}
