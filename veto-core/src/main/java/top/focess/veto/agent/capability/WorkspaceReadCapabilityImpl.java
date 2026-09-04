package top.focess.veto.agent.capability;

import com.fasterxml.jackson.databind.node.NullNode;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.intercept.ToolExecutionPermit;
import top.focess.veto.agent.mcp.ToolErrors;
import top.focess.veto.agent.mcp.ToolJson;

/** The only low-level filesystem implementation behind {@link WorkspaceReadCapability}. */
final class WorkspaceReadCapabilityImpl implements WorkspaceReadCapability {

    private static final int LIST_MAX_ENTRIES = 5000;
    private static final long TEXT_MAX_BYTES = 16L * 1024 * 1024;
    private static final int TEXT_MAX_OUTPUT_LINES = 5000;
    private static final int TEXT_MAX_OUTPUT_CHARS = 1_000_000;
    private static final int FIND_MAX_RESULTS = 5000;
    private static final int FIND_MAX_VISITED = 50_000;
    private static final int FIND_MAX_JSON_BYTES = 1024 * 1024;
    private static final int GREP_MAX_FILES = 10_000;
    private static final int GREP_MAX_MATCHES = 2_000;
    private static final int GREP_MAX_OUTPUT_CHARS = 1_000_000;
    private static final Duration MAX_DURATION = Duration.ofSeconds(10);

    private final @NonNull ToolExecutionPermit permit;

    WorkspaceReadCapabilityImpl(@NonNull ToolExecutionPermit permit) {
        this.permit = permit;
    }

    @Override
    public @NonNull String listDirectory(@NonNull String pathArgument) {
        ToolExecutionPermit.AuthorizedPath authorized = authorizedPath(pathArgument);
        Path directory = requiredHostPath(authorized);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            return ToolErrors.failure(
                    "NOT_A_DIRECTORY", "Not a directory: " + authorized.requestedPath());
        }
        refuseProtectedRoot(directory, authorized.requestedPath());
        List<Path> entries = new ArrayList<>(LIST_MAX_ENTRIES + 1);
        try (var stream = Files.list(directory)) {
            var iterator = stream.iterator();
            while (iterator.hasNext() && entries.size() <= LIST_MAX_ENTRIES) {
                Path entry = iterator.next();
                if (!entry.toAbsolutePath().normalize().startsWith(directory)
                        || isProtected(entry)
                        || Files.isSymbolicLink(entry)
                        || isReparsePoint(entry)) {
                    continue;
                }
                entries.add(entry);
            }
        } catch (IOException | java.io.UncheckedIOException e) {
            return ToolErrors.failure(
                    "IO_ERROR", "Cannot list directory: " + authorized.requestedPath());
        }
        boolean truncated = entries.size() > LIST_MAX_ENTRIES;
        if (truncated) {
            entries.remove(entries.size() - 1);
        }
        entries.sort(Comparator.comparing(WorkspaceReadCapabilityImpl::fileName));
        StringBuilder output = new StringBuilder();
        for (Path entry : entries) {
            output.append(fileName(entry));
            if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
                output.append('/');
            }
            output.append('\n');
        }
        if (truncated) {
            output.append("[truncated at ").append(LIST_MAX_ENTRIES).append(" entries]\n");
        }
        return output.toString();
    }

    @Override
    public @NonNull String readText(
            @NonNull String pathArgument, Integer startLine, Integer endLine) {
        ToolExecutionPermit.AuthorizedPath authorized = authorizedPath(pathArgument);
        Path file = requiredHostPath(authorized);
        refuseProtectedRoot(file, authorized.requestedPath());
        if (Files.isSymbolicLink(file) || isReparsePoint(file)) {
            return ToolErrors.failure(
                    "UNSAFE_LINK",
                    "File is a symbolic link or reparse point: " + authorized.requestedPath());
        }
        final BasicFileAttributes attributes;
        try {
            attributes =
                    Files.readAttributes(
                            file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException e) {
            return ToolErrors.failure(
                    "NOT_A_FILE", "Not a regular file: " + authorized.requestedPath());
        }
        if (!attributes.isRegularFile()) {
            return ToolErrors.failure(
                    "NOT_A_FILE", "Not a regular file: " + authorized.requestedPath());
        }
        if (attributes.size() > TEXT_MAX_BYTES) {
            return ToolErrors.failure(
                    "FILE_TOO_LARGE",
                    "File exceeds 16 MiB (16,777,216 bytes); request a smaller artifact");
        }
        int from = startLine == null ? 1 : Math.max(1, startLine);
        int requestedEnd = endLine == null ? Integer.MAX_VALUE : endLine;
        if (requestedEnd < from) {
            return "";
        }
        StringBuilder output = new StringBuilder();
        int emitted = 0;
        boolean truncated = false;
        try (SeekableByteChannel channel =
                        Files.newByteChannel(
                                file,
                                Set.<OpenOption>of(
                                        StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
                BufferedReader reader =
                        new BufferedReader(Channels.newReader(channel, StandardCharsets.UTF_8))) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (lineNumber < from) {
                    continue;
                }
                if (lineNumber > requestedEnd) {
                    break;
                }
                String rendered = lineNumber + ": " + line + "\n";
                if (emitted >= TEXT_MAX_OUTPUT_LINES
                        || output.length() + rendered.length() > TEXT_MAX_OUTPUT_CHARS) {
                    truncated = true;
                    break;
                }
                output.append(rendered);
                emitted++;
            }
        } catch (java.nio.charset.MalformedInputException e) {
            return ToolErrors.failure(
                    "INVALID_UTF8", "File is not valid UTF-8: " + authorized.requestedPath());
        } catch (IOException e) {
            return ToolErrors.failure(
                    "IO_ERROR", "Cannot read file: " + authorized.requestedPath());
        }
        if (truncated) {
            output.append("[truncated; request a narrower line range]\n");
        }
        return output.toString();
    }

    @Override
    public @NonNull String findFiles(@NonNull String pathArgument, @NonNull String pattern) {
        ToolExecutionPermit.AuthorizedPath authorized = authorizedPath(pathArgument);
        Path base = requiredHostPath(authorized);
        if (!Files.isDirectory(base, LinkOption.NOFOLLOW_LINKS)) {
            return ToolErrors.failure(
                    "NOT_A_DIRECTORY", "Not a directory: " + authorized.requestedPath());
        }
        refuseProtectedRoot(base, authorized.requestedPath());
        if (pattern.isBlank() || pattern.indexOf('\\') >= 0) {
            return ToolErrors.failure(
                    "INVALID_PATTERN", "Pattern must be non-blank and use '/' separators.");
        }
        Pattern matcher;
        try {
            matcher = Pattern.compile(globRegex(pattern));
        } catch (RuntimeException e) {
            return ToolErrors.failure("INVALID_PATTERN", "Invalid file pattern: " + pattern);
        }

        FindState state = new FindState();
        FindVisitor visitor = new FindVisitor(base, matcher, state);
        try {
            Files.walkFileTree(base, visitor);
        } catch (IOException e) {
            return ToolErrors.failure(
                    "IO_ERROR", "Cannot search directory: " + authorized.requestedPath());
        }
        if (state.truncationReason == null && visitor.boundaryTruncationReason() != null) {
            state.truncationReason = visitor.boundaryTruncationReason();
        }
        state.matches.sort(Comparator.naturalOrder());
        while (true) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("base", authorized.requestedPath());
            result.put("pattern", pattern);
            result.put("matches", List.copyOf(state.matches));
            result.put("truncated", state.truncationReason != null);
            result.put(
                    "truncationReason",
                    state.truncationReason == null
                            ? NullNode.getInstance()
                            : state.truncationReason);
            result.put("skippedEntries", visitor.skippedEntries());
            String json = ToolJson.object(result);
            if (json.getBytes(StandardCharsets.UTF_8).length <= FIND_MAX_JSON_BYTES
                    || state.matches.isEmpty()) {
                return json;
            }
            state.matches.remove(state.matches.size() - 1);
            state.truncationReason = "OUTPUT_LIMIT";
        }
    }

    @Override
    public @NonNull String grep(
            @NonNull String pathArgument,
            @NonNull String query,
            boolean caseInsensitive,
            List<String> includes) {
        if (query.isEmpty()) {
            return ToolErrors.failure("INVALID_QUERY", "query must not be empty");
        }
        ToolExecutionPermit.AuthorizedPath authorized = authorizedPath(pathArgument);
        Path root = requiredHostPath(authorized);
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return ToolErrors.failure(
                    "PATH_NOT_FOUND", "Search path does not exist: " + authorized.requestedPath());
        }
        refuseProtectedRoot(root, authorized.requestedPath());
        if (Files.isSymbolicLink(root) || isReparsePoint(root)) {
            return ToolErrors.failure(
                    "UNSAFE_LINK", "Search path is a symbolic link or reparse point");
        }
        List<PathMatcher> includeMatchers;
        try {
            includeMatchers = compileIncludes(includes);
        } catch (IllegalArgumentException e) {
            return ToolErrors.failure("INVALID_PATTERN", "Invalid includes glob");
        }
        GrepState state = new GrepState(caseInsensitive, query);
        GrepVisitor visitor =
                new GrepVisitor(root, authorized.requestedPath(), includeMatchers, state);
        try {
            Files.walkFileTree(root, visitor);
        } catch (IOException e) {
            return ToolErrors.failure(
                    "IO_ERROR", "Cannot search path: " + authorized.requestedPath());
        }
        String boundaryReason = visitor.boundaryTruncationReason();
        if (state.truncationReason == null && boundaryReason != null) {
            state.truncationReason = boundaryReason.toLowerCase(Locale.ROOT);
        }
        if (state.truncationReason != null) {
            state.output.append("[truncated: ").append(state.truncationReason).append("]\n");
        }
        return state.output.isEmpty() ? "(no matches)" : state.output.toString();
    }

    private ToolExecutionPermit.@NonNull AuthorizedPath authorizedPath(
            @NonNull String argumentName) {
        ToolExecutionPermit.AuthorizedPath authorized = permit.path(argumentName);
        if (authorized == null || authorized.hostPath() == null) {
            throw new SecurityException(
                    "Missing authorized filesystem target for parameter '" + argumentName + "'");
        }
        return authorized;
    }

    private static @NonNull Path requiredHostPath(
            ToolExecutionPermit.@NonNull AuthorizedPath authorized) {
        Path path = authorized.hostPath();
        if (path == null) {
            throw new SecurityException(
                    "Missing authorized filesystem target for parameter '"
                            + authorized.argumentName()
                            + "'");
        }
        return path.toAbsolutePath().normalize();
    }

    private void refuseProtectedRoot(@NonNull Path root, @NonNull String requestedPath) {
        if (isProtected(root)) {
            ToolErrors.failure("PATH_PROTECTED", "Protected path cannot be read: " + requestedPath);
        }
    }

    private boolean isProtected(@NonNull Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        return permit.protectedPaths().stream().anyMatch(normalized::startsWith);
    }

    private static boolean isReparsePoint(@NonNull Path path) {
        try {
            Object value = Files.getAttribute(path, "dos:reparsePoint", LinkOption.NOFOLLOW_LINKS);
            return Boolean.TRUE.equals(value);
        } catch (IOException | UnsupportedOperationException | IllegalArgumentException e) {
            return false;
        }
    }

    private static @NonNull String fileName(@NonNull Path path) {
        Path name = path.getFileName();
        if (name == null) {
            throw new IllegalArgumentException("Directory entry has no file name");
        }
        return name.toString();
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
                if (".[]{}()+-^$|\\".indexOf(current) >= 0) {
                    regex.append('\\');
                }
                regex.append(current);
            }
        }
        return regex.append('$').toString();
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

    private abstract class AuthorizedTreeVisitor extends SimpleFileVisitor<Path> {
        private final @NonNull Path root;
        private final @NonNull Instant startedAt = Instant.now();
        private final int maxVisited;
        private int visited;
        private int skippedEntries;
        private String boundaryTruncationReason;

        AuthorizedTreeVisitor(@NonNull Path root, int maxVisited) {
            this.root = root.toAbsolutePath().normalize();
            this.maxVisited = maxVisited;
        }

        @Override
        public final @NonNull FileVisitResult preVisitDirectory(
                @NonNull Path dir, @NonNull BasicFileAttributes attrs) {
            if (!withinBudget()) {
                return FileVisitResult.TERMINATE;
            }
            if (!withinRoot(dir)
                    || isProtected(dir)
                    || attrs.isSymbolicLink()
                    || Files.isSymbolicLink(dir)
                    || isReparsePoint(dir)) {
                skippedEntries++;
                return FileVisitResult.SKIP_SUBTREE;
            }
            return FileVisitResult.CONTINUE;
        }

        @Override
        public final @NonNull FileVisitResult visitFile(
                @NonNull Path file, @NonNull BasicFileAttributes attrs) throws IOException {
            if (!withinBudget()) {
                return FileVisitResult.TERMINATE;
            }
            if (!withinRoot(file)
                    || isProtected(file)
                    || attrs.isSymbolicLink()
                    || Files.isSymbolicLink(file)
                    || isReparsePoint(file)) {
                skippedEntries++;
                return FileVisitResult.CONTINUE;
            }
            if (!attrs.isRegularFile()) {
                skippedEntries++;
                return FileVisitResult.CONTINUE;
            }
            return visitAuthorizedFile(file, attrs);
        }

        @Override
        public final @NonNull FileVisitResult visitFileFailed(
                @NonNull Path file, @NonNull IOException exc) {
            skippedEntries++;
            return FileVisitResult.CONTINUE;
        }

        @Override
        public final @NonNull FileVisitResult postVisitDirectory(Path dir, IOException exc) {
            if (exc != null) {
                skippedEntries++;
            }
            return FileVisitResult.CONTINUE;
        }

        abstract @NonNull FileVisitResult visitAuthorizedFile(
                @NonNull Path file, @NonNull BasicFileAttributes attributes) throws IOException;

        final int skippedEntries() {
            return skippedEntries;
        }

        final String boundaryTruncationReason() {
            return boundaryTruncationReason;
        }

        private boolean withinRoot(@NonNull Path path) {
            return path.toAbsolutePath().normalize().startsWith(root);
        }

        private boolean withinBudget() {
            visited++;
            if (visited > maxVisited) {
                boundaryTruncationReason = "VISIT_LIMIT";
                return false;
            }
            if (Duration.between(startedAt, Instant.now()).compareTo(MAX_DURATION) > 0) {
                boundaryTruncationReason = "TIME_LIMIT";
                return false;
            }
            return true;
        }
    }

    private final class FindVisitor extends AuthorizedTreeVisitor {
        private final @NonNull Path base;
        private final @NonNull Pattern matcher;
        private final @NonNull FindState state;

        FindVisitor(@NonNull Path base, @NonNull Pattern matcher, @NonNull FindState state) {
            super(base, FIND_MAX_VISITED);
            this.base = base;
            this.matcher = matcher;
            this.state = state;
        }

        @Override
        @NonNull FileVisitResult visitAuthorizedFile(
                @NonNull Path file, @NonNull BasicFileAttributes attributes) {
            String portable = base.relativize(file).toString().replace('\\', '/');
            if (matcher.matcher(portable).matches()) {
                state.matches.add(portable);
                if (state.matches.size() > FIND_MAX_RESULTS) {
                    state.matches.remove(state.matches.size() - 1);
                    state.truncationReason = "RESULT_LIMIT";
                    return FileVisitResult.TERMINATE;
                }
            }
            return FileVisitResult.CONTINUE;
        }
    }

    private final class GrepVisitor extends AuthorizedTreeVisitor {
        private final @NonNull Path root;
        private final @NonNull String requestedRoot;
        private final @NonNull List<PathMatcher> includes;
        private final @NonNull GrepState state;

        GrepVisitor(
                @NonNull Path root,
                @NonNull String requestedRoot,
                @NonNull List<PathMatcher> includes,
                @NonNull GrepState state) {
            super(root, FIND_MAX_VISITED);
            this.root = root;
            this.requestedRoot = requestedRoot;
            this.includes = includes;
            this.state = state;
        }

        @Override
        @NonNull FileVisitResult visitAuthorizedFile(
                @NonNull Path file, @NonNull BasicFileAttributes attributes) {
            if (!matchesIncludes(root, file, includes)) {
                return FileVisitResult.CONTINUE;
            }
            if (state.visitedFiles >= GREP_MAX_FILES) {
                state.truncationReason = "file limit " + GREP_MAX_FILES;
                return FileVisitResult.TERMINATE;
            }
            state.visitedFiles++;
            try (SeekableByteChannel channel =
                            Files.newByteChannel(
                                    file,
                                    Set.<OpenOption>of(
                                            StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
                    BufferedReader reader =
                            new BufferedReader(
                                    Channels.newReader(channel, StandardCharsets.UTF_8))) {
                String line;
                int lineNumber = 0;
                while ((line = reader.readLine()) != null) {
                    lineNumber++;
                    String candidate = state.caseInsensitive ? line.toLowerCase(Locale.ROOT) : line;
                    if (!candidate.contains(state.query)) {
                        continue;
                    }
                    String rendered =
                            displayPath(requestedRoot, root, file)
                                    + ":"
                                    + lineNumber
                                    + ": "
                                    + line
                                    + "\n";
                    if (state.matches >= GREP_MAX_MATCHES
                            || state.output.length() + rendered.length() > GREP_MAX_OUTPUT_CHARS) {
                        state.truncationReason =
                                state.matches >= GREP_MAX_MATCHES
                                        ? "match limit " + GREP_MAX_MATCHES
                                        : "output limit " + GREP_MAX_OUTPUT_CHARS + " chars";
                        return FileVisitResult.TERMINATE;
                    }
                    state.output.append(rendered);
                    state.matches++;
                }
            } catch (IOException ignored) {
                // Unreadable, changing, or non-UTF-8 files are skipped as one bounded entry.
            }
            return FileVisitResult.CONTINUE;
        }
    }

    private static boolean matchesIncludes(
            @NonNull Path root, @NonNull Path file, @NonNull List<PathMatcher> includes) {
        if (includes.isEmpty()) {
            return true;
        }
        Path relative =
                Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
                        ? root.relativize(file)
                        : file.getFileName();
        Path fileName = file.getFileName();
        return relative != null
                && fileName != null
                && includes.stream()
                        .anyMatch(
                                matcher -> matcher.matches(relative) || matcher.matches(fileName));
    }

    private static @NonNull String displayPath(
            @NonNull String requestedRoot, @NonNull Path root, @NonNull Path file) {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            return requestedRoot;
        }
        String relative = root.relativize(file).toString().replace('\\', '/');
        String separator = requestedRoot.endsWith("/") || requestedRoot.endsWith("\\") ? "" : "/";
        return requestedRoot + separator + relative;
    }

    private static final class FindState {
        private final @NonNull List<String> matches = new ArrayList<>();
        private String truncationReason;
    }

    private static final class GrepState {
        private final boolean caseInsensitive;
        private final @NonNull String query;
        private final @NonNull StringBuilder output = new StringBuilder();
        private int visitedFiles;
        private int matches;
        private String truncationReason;

        GrepState(boolean caseInsensitive, @NonNull String query) {
            this.caseInsensitive = caseInsensitive;
            this.query = caseInsensitive ? query.toLowerCase(Locale.ROOT) : query;
        }
    }
}
