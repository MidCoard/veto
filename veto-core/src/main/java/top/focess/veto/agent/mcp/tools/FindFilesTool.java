package top.focess.veto.agent.mcp.tools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.mcp.Doc;
import top.focess.veto.agent.mcp.NativeTool;
import top.focess.veto.agent.mcp.ParamCategory;
import top.focess.veto.agent.mcp.RiskCategory;
import top.focess.veto.agent.mcp.SecurityHint;
import top.focess.veto.agent.mcp.ToolCallContextHolder;
import top.focess.veto.agent.mcp.ToolCapability;
import top.focess.veto.agent.mcp.ToolDoc;
import top.focess.veto.agent.mcp.ToolDocs;
import top.focess.veto.agent.mcp.ToolErrors;
import top.focess.veto.agent.mcp.ToolJson;
import top.focess.veto.agent.mcp.ToolResultFormat;
import top.focess.veto.agent.mcp.ToolSecurity;

/** Finds regular files below one Gateway-authorized directory without following links. */
@Component
@ToolSecurity(risk = RiskCategory.READ_ONLY, capability = ToolCapability.WORKSPACE_READ)
public final class FindFilesTool implements NativeTool<FindFilesTool.Args> {

    private static final int DEFAULT_MAX_RESULTS = 500;
    private static final int MAX_RESULTS = 5000;
    private static final int MAX_VISITED = 50_000;
    private static final int MAX_JSON_BYTES = 1024 * 1024;
    private static final Duration MAX_DURATION = Duration.ofSeconds(10);

    @ToolDoc(
            resultFormats = {ToolResultFormat.JSON},
            description = "Find regular files below an authorized directory using a portable glob.",
            behavior =
                    "Searches recursively without following symbolic links or directory reparse points. "
                            + "The pattern uses `/` separators and supports `*`, `**`, and `?`; results are sorted lexicographically.",
            whenToUse = "Use it when you know a filename or portable glob but not its exact path.",
            whenNotToUse =
                    "Do not use it to search file contents; use grep_search. Do not use it when the exact path is already known.",
            resultContract =
                    "Returns JSON with `base`, `pattern`, `matches`, `truncated`, `truncationReason`, and `skippedEntries`. "
                            + "Failures use stable codes NOT_A_DIRECTORY, INVALID_PATTERN, INVALID_LIMIT, or IO_ERROR.",
            errorsAndEdgeCases =
                    "Search stops at the requested result count, 50000 visited entries, 1 MiB encoded output, or 10 seconds. "
                            + "Unreadable entries are skipped and counted. `**/*.java` also matches a Java file directly below the base.",
            security =
                    "Workspace read tool. The Gateway resolves and authorizes `absolutePath` before execution.",
            examples = {
                "{\"absolutePath\":\"<workspace-root>\",\"pattern\":\"**/*.java\"}",
                "{\"absolutePath\":\"<workspace-root>\",\"pattern\":\"*.md\",\"maxResults\":100}"
            },
            returnExamples = {
                "{\"base\":\"<workspace-root>\",\"pattern\":\"**/*.java\",\"matches\":[\"src/Main.java\"],\"truncated\":false,\"truncationReason\":null,\"skippedEntries\":0}"
            })
    public record Args(
            @NonNull
                    @SecurityHint(ParamCategory.FILESYSTEM_PATH)
                    @Doc("Absolute directory path to search below.")
                    String absolutePath,
            @NonNull @Doc("Portable relative-path glob using `*`, `**`, and `?`.") String pattern,
            @Doc("Maximum matches to return; default 500, range 1 through 5000.")
                    Integer maxResults) {}

    @Override
    public @NonNull String getName() {
        return "find_files";
    }

    @Override
    public @NonNull String getDescription() {
        return "Find regular files below a directory using a portable glob.";
    }

    @Override
    public @NonNull Class<Args> getArgsClass() {
        return ToolDocs.nonNullClass(Args.class);
    }

    @Override
    public @NonNull String execute(@NonNull Args args) {
        Path base = Path.of(args.absolutePath()).toAbsolutePath().normalize();
        if (!Files.isDirectory(base)) {
            return ToolErrors.failure(
                    "NOT_A_DIRECTORY",
                    "Not a directory: " + requestedPath("absolutePath", args.absolutePath()));
        }
        if (args.pattern().isBlank() || args.pattern().indexOf('\\') >= 0) {
            return ToolErrors.failure(
                    "INVALID_PATTERN", "Pattern must be non-blank and use '/' separators.");
        }
        int limit = args.maxResults() == null ? DEFAULT_MAX_RESULTS : args.maxResults();
        if (limit < 1 || limit > MAX_RESULTS) {
            return ToolErrors.failure("INVALID_LIMIT", "maxResults must be between 1 and 5000.");
        }
        Pattern matcher;
        try {
            matcher = Pattern.compile(globRegex(args.pattern()));
        } catch (RuntimeException e) {
            return ToolErrors.failure("INVALID_PATTERN", "Invalid file pattern: " + args.pattern());
        }

        SearchState state = new SearchState(limit, Instant.now());
        try {
            Files.walkFileTree(base, new Finder(base, matcher, state));
        } catch (SearchStopped ignored) {
            // A normal bounded-search stop; the reason is already stored in state.
        } catch (IOException e) {
            return ToolErrors.failure(
                    "IO_ERROR",
                    "Cannot search directory: "
                            + requestedPath("absolutePath", args.absolutePath()));
        }
        state.matches.sort(Comparator.naturalOrder());
        String requestedBase = requestedPath("absolutePath", args.absolutePath());
        while (true) {
            Map<String, Object> result = result(requestedBase, args.pattern(), state);
            String json = ToolJson.object(result);
            if (json.getBytes(StandardCharsets.UTF_8).length <= MAX_JSON_BYTES
                    || state.matches.isEmpty()) {
                return json;
            }
            state.matches.remove(state.matches.size() - 1);
            state.truncationReason = "OUTPUT_LIMIT";
        }
    }

    private static @NonNull Map<String, Object> result(
            @NonNull String base, @NonNull String pattern, @NonNull SearchState state) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("base", base);
        result.put("pattern", pattern);
        result.put("matches", List.copyOf(state.matches));
        result.put("truncated", state.truncationReason != null);
        result.put(
                "truncationReason",
                state.truncationReason == null
                        ? com.fasterxml.jackson.databind.node.NullNode.getInstance()
                        : state.truncationReason);
        result.put("skippedEntries", state.skippedEntries);
        return result;
    }

    private static @NonNull String requestedPath(@NonNull String name, @NonNull String fallback) {
        var context = ToolCallContextHolder.get();
        var path = context == null ? null : context.executionPermit().path(name);
        return path == null ? fallback : path.requestedPath();
    }

    private static boolean isReparsePoint(@NonNull Path path) {
        try {
            Object value =
                    Files.getAttribute(
                            path, "dos:reparsePoint", java.nio.file.LinkOption.NOFOLLOW_LINKS);
            return Boolean.TRUE.equals(value);
        } catch (IOException | UnsupportedOperationException | IllegalArgumentException e) {
            return false;
        }
    }

    private static @NonNull String globRegex(@NonNull String glob) {
        StringBuilder regex = new StringBuilder("^");
        for (int index = 0; index < glob.length(); index++) {
            char current = glob.charAt(index);
            if (current == '*') {
                boolean doubleStar = index + 1 < glob.length() && glob.charAt(index + 1) == '*';
                if (doubleStar) {
                    index++;
                    if (index + 1 < glob.length() && glob.charAt(index + 1) == '/') {
                        index++;
                        regex.append("(?:.*/)?");
                    } else {
                        regex.append(".*");
                    }
                } else {
                    regex.append("[^/]*");
                }
            } else if (current == '?') {
                regex.append("[^/]");
            } else {
                if (".[]{}()+-^$|\\".indexOf(current) >= 0) regex.append('\\');
                regex.append(current);
            }
        }
        return regex.append('$').toString();
    }

    private static final class Finder implements FileVisitor<Path> {
        private final @NonNull Path base;
        private final @NonNull Pattern matcher;
        private final @NonNull SearchState state;

        Finder(@NonNull Path base, @NonNull Pattern matcher, @NonNull SearchState state) {
            this.base = base;
            this.matcher = matcher;
            this.state = state;
        }

        @Override
        public @NonNull FileVisitResult preVisitDirectory(
                @NonNull Path dir, @NonNull BasicFileAttributes attrs) {
            checkBound();
            if (!dir.equals(base) && (Files.isSymbolicLink(dir) || isReparsePoint(dir))) {
                state.skippedEntries++;
                return FileVisitResult.SKIP_SUBTREE;
            }
            return FileVisitResult.CONTINUE;
        }

        @Override
        public @NonNull FileVisitResult visitFile(
                @NonNull Path file, @NonNull BasicFileAttributes attrs) {
            checkBound();
            if (attrs.isRegularFile() && !Files.isSymbolicLink(file)) {
                Path relative = base.relativize(file);
                String portable = relative.toString().replace('\\', '/');
                if (matcher.matcher(portable).matches()) {
                    state.matches.add(portable);
                    if (state.matches.size() > state.limit) {
                        state.matches.remove(state.matches.size() - 1);
                        state.truncationReason = "RESULT_LIMIT";
                        throw new SearchStopped();
                    }
                }
            }
            return FileVisitResult.CONTINUE;
        }

        @Override
        public @NonNull FileVisitResult visitFileFailed(
                @NonNull Path file, @NonNull IOException exc) {
            state.skippedEntries++;
            return FileVisitResult.CONTINUE;
        }

        @Override
        public @NonNull FileVisitResult postVisitDirectory(Path dir, IOException exc) {
            if (exc != null) state.skippedEntries++;
            return FileVisitResult.CONTINUE;
        }

        private void checkBound() {
            state.visited++;
            if (state.visited > MAX_VISITED) {
                state.truncationReason = "VISIT_LIMIT";
                throw new SearchStopped();
            }
            if (Duration.between(state.startedAt, Instant.now()).compareTo(MAX_DURATION) > 0) {
                state.truncationReason = "TIME_LIMIT";
                throw new SearchStopped();
            }
        }
    }

    private static final class SearchState {
        final int limit;
        final @NonNull Instant startedAt;
        final @NonNull List<String> matches = new ArrayList<>();
        int visited;
        int skippedEntries;
        String truncationReason;

        SearchState(int limit, @NonNull Instant startedAt) {
            this.limit = limit;
            this.startedAt = startedAt;
        }
    }

    @SuppressWarnings("serial")
    private static final class SearchStopped extends RuntimeException {}
}
