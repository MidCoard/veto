package top.focess.veto.agent.mcp.tools;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileStore;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
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

/** Moves one file, link, or bounded directory tree without overwrite or copy-delete fallback. */
@Component
@ToolSecurity(risk = RiskCategory.FILE_WRITE, capability = ToolCapability.WORKSPACE_WRITE)
public final class MovePathTool implements NativeTool<MovePathTool.Args> {

    private static final int MAX_ENTRIES = 50_000;
    private static final Duration MAX_DURATION = Duration.ofSeconds(10);

    @ToolDoc(
            resultFormats = {ToolResultFormat.JSON},
            description =
                    "Move or rename one authorized file, link, or directory without overwriting.",
            behavior =
                    "Moves the source to an existing destination parent. It never overwrites and never falls back to copy-then-delete. "
                            + "Directory preflight does not follow links and is bounded to 50000 entries or 10 seconds.",
            whenToUse = "Use it to rename or relocate a file, symbolic link, or directory tree.",
            whenNotToUse = "Do not use it to copy content or replace an existing destination.",
            resultContract =
                    "Success returns JSON with `status`, `source`, `destination`, and `kind`. Failures use SOURCE_NOT_FOUND, "
                            + "DESTINATION_EXISTS, INVALID_DESTINATION, CROSS_FILESYSTEM_MOVE, MOVE_LIMIT_EXCEEDED, SAFE_MOVE_UNAVAILABLE, or IO_ERROR.",
            errorsAndEdgeCases =
                    "The destination parent must already exist. Symbolic links are moved as links. Protected descendants reject a directory move before mutation.",
            security =
                    "Workspace write tool. Both paths are independently resolved, screened, approved, and rebound by the Gateway.",
            examples = {
                "{\"sourceAbsolutePath\":\"<workspace-root>/old.txt\",\"destinationAbsolutePath\":\"<workspace-root>/new.txt\"}"
            },
            returnExamples = {
                "{\"status\":\"moved\",\"source\":\"<workspace-root>/old.txt\",\"destination\":\"<workspace-root>/new.txt\",\"kind\":\"file\"}"
            })
    public record Args(
            @NonNull @SecurityHint(ParamCategory.FILESYSTEM_PATH) @Doc("Absolute source path.")
                    String sourceAbsolutePath,
            @NonNull @SecurityHint(ParamCategory.FILESYSTEM_PATH) @Doc("Absolute destination path.")
                    String destinationAbsolutePath) {}

    @Override
    public @NonNull String getName() {
        return "move_path";
    }

    @Override
    public @NonNull String getDescription() {
        return "Move or rename one file, link, or directory without overwriting.";
    }

    @Override
    public @NonNull Class<Args> getArgsClass() {
        return ToolDocs.nonNullClass(Args.class);
    }

    @Override
    public @NonNull String execute(@NonNull Args args) {
        Path source = Path.of(args.sourceAbsolutePath()).toAbsolutePath().normalize();
        Path destination = Path.of(args.destinationAbsolutePath()).toAbsolutePath().normalize();
        String requestedSource = requestedPath("sourceAbsolutePath", args.sourceAbsolutePath());
        String requestedDestination =
                requestedPath("destinationAbsolutePath", args.destinationAbsolutePath());
        if (!Files.exists(source, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return ToolErrors.failure(
                    "SOURCE_NOT_FOUND", "Source path not found: " + requestedSource);
        }
        if (Files.exists(destination, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return ToolErrors.failure(
                    "DESTINATION_EXISTS", "Destination already exists: " + requestedDestination);
        }
        Path parent = destination.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            return ToolErrors.failure(
                    "INVALID_DESTINATION",
                    "Destination parent is not a directory: " + requestedDestination);
        }
        if (destination.startsWith(source) && !destination.equals(source)) {
            return ToolErrors.failure(
                    "INVALID_DESTINATION", "A directory cannot be moved inside itself.");
        }
        if (Files.isDirectory(source, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            verifyBoundedTree(source);
            verifyNoProtectedDescendant(source);
        }
        try {
            FileStore sourceStore = Files.getFileStore(source);
            FileStore destinationStore = Files.getFileStore(parent);
            if (!sourceStore.equals(destinationStore)) {
                return ToolErrors.failure(
                        "CROSS_FILESYSTEM_MOVE",
                        "Source and destination are on different filesystems.");
            }
            String kind = kind(source);
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "moved");
            result.put("source", requestedSource);
            result.put("destination", requestedDestination);
            result.put("kind", kind);
            return ToolJson.object(result);
        } catch (AtomicMoveNotSupportedException e) {
            return ToolErrors.failure(
                    "SAFE_MOVE_UNAVAILABLE", "The filesystem cannot perform this move atomically.");
        } catch (IOException | SecurityException e) {
            return ToolErrors.failure("IO_ERROR", "Cannot move path: " + requestedSource);
        }
    }

    private static void verifyBoundedTree(@NonNull Path source) {
        Instant started = Instant.now();
        int[] entries = {0};
        try {
            Files.walkFileTree(
                    source,
                    new SimpleFileVisitor<>() {
                        @Override
                        public @NonNull FileVisitResult preVisitDirectory(
                                @NonNull Path dir, @NonNull BasicFileAttributes attrs) {
                            check();
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public @NonNull FileVisitResult visitFile(
                                @NonNull Path file, @NonNull BasicFileAttributes attrs) {
                            check();
                            return FileVisitResult.CONTINUE;
                        }

                        private void check() {
                            entries[0]++;
                            if (entries[0] > MAX_ENTRIES
                                    || Duration.between(started, Instant.now())
                                                    .compareTo(MAX_DURATION)
                                            > 0) {
                                throw new MoveLimitExceeded();
                            }
                        }
                    });
        } catch (MoveLimitExceeded e) {
            ToolErrors.failure(
                    "MOVE_LIMIT_EXCEEDED", "Directory move preflight exceeded its safety limit.");
        } catch (IOException e) {
            ToolErrors.failure("IO_ERROR", "Cannot inspect source directory before moving it.");
        }
    }

    private static void verifyNoProtectedDescendant(@NonNull Path source) {
        var context = ToolCallContextHolder.get();
        if (context == null) return;
        for (Path protectedPath : context.executionPermit().protectedPaths()) {
            if (protectedPath.startsWith(source)) {
                ToolErrors.refused(
                        "DESCENDANT_REFUSED", "Source directory contains a protected path.");
            }
        }
    }

    private static @NonNull String requestedPath(@NonNull String name, @NonNull String fallback) {
        var context = ToolCallContextHolder.get();
        var path = context == null ? null : context.executionPermit().path(name);
        return path == null ? fallback : path.requestedPath();
    }

    private static @NonNull String kind(@NonNull Path path) {
        if (Files.isSymbolicLink(path)) return "link";
        if (Files.isDirectory(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return "directory";
        return "file";
    }

    @SuppressWarnings("serial")
    private static final class MoveLimitExceeded extends RuntimeException {}
}
