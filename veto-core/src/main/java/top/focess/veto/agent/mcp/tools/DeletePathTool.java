package top.focess.veto.agent.mcp.tools;

import java.io.IOException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.mcp.Doc;
import top.focess.veto.agent.mcp.NativeTool;
import top.focess.veto.agent.mcp.ParamCategory;
import top.focess.veto.agent.mcp.Required;
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
import top.focess.veto.agent.screening.Danger;

/** Deletes one authorized path, with explicit recursive intent for non-empty directories. */
@Component
@ToolSecurity(
        risk = RiskCategory.FILE_WRITE,
        capability = ToolCapability.WORKSPACE_WRITE,
        requiresSemanticScreening = true,
        minimumDanger = Danger.DANGEROUS)
public final class DeletePathTool implements NativeTool<DeletePathTool.Args> {

    private static final int MAX_ENTRIES = 50_000;
    private static final Duration MAX_DURATION = Duration.ofSeconds(10);

    @ToolDoc(
            resultFormats = {ToolResultFormat.JSON},
            description =
                    "Delete one authorized file, link, or directory with explicit recursive intent.",
            behavior =
                    "Deletes a file or link directly. An empty directory can be deleted with recursive=false; a non-empty directory requires recursive=true. "
                            + "Recursive deletion performs a bounded no-follow preflight and then deletes children before parents.",
            whenToUse = "Use it only when the requested path must be removed.",
            whenNotToUse =
                    "Do not use it to clear generated output when a narrower build-tool cleanup is available. Do not use recursive=true speculatively.",
            resultContract =
                    "Success returns JSON with `status`, `path`, `kind`, and `entriesDeleted`. Failures use PATH_NOT_FOUND, DIRECTORY_NOT_EMPTY, "
                            + "DELETE_LIMIT_EXCEEDED, SAFE_DELETE_UNAVAILABLE, TREE_CHANGED, or IO_ERROR; protected descendants are REFUSED with DESCENDANT_REFUSED.",
            errorsAndEdgeCases =
                    "Links are deleted as links and never traversed. Recursive preflight is limited to 50000 entries or 10 seconds. "
                            + "If the tree changes after preflight, deletion stops and reports TREE_CHANGED.",
            security =
                    "Workspace write tool with a deterministic DANGEROUS floor, mandatory semantic screening, and human approval when required.",
            examples = {
                "{\"absolutePath\":\"<workspace-root>/obsolete.txt\",\"recursive\":false}",
                "{\"absolutePath\":\"<workspace-root>/generated\",\"recursive\":true}"
            },
            returnExamples = {
                "{\"status\":\"deleted\",\"path\":\"<workspace-root>/obsolete.txt\",\"kind\":\"file\",\"entriesDeleted\":1}"
            })
    public record Args(
            @NonNull @SecurityHint(ParamCategory.FILESYSTEM_PATH) @Doc("Absolute path to delete.")
                    String absolutePath,
            @Required @Doc("Whether deletion may recurse through a non-empty directory.")
                    boolean recursive) {}

    @Override
    public @NonNull String getName() {
        return "delete_path";
    }

    @Override
    public @NonNull String getDescription() {
        return "Delete one file, link, or directory with explicit recursive intent.";
    }

    @Override
    public @NonNull Class<Args> getArgsClass() {
        return ToolDocs.nonNullClass(Args.class);
    }

    @Override
    public @NonNull String execute(@NonNull Args args) {
        Path path = Path.of(args.absolutePath()).toAbsolutePath().normalize();
        String requested = requestedPath(args.absolutePath());
        if (!Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return ToolErrors.failure("PATH_NOT_FOUND", "Path not found: " + requested);
        }
        String kind = kind(path);
        verifyNoProtectedDescendant(path);
        if (!args.recursive() || !"directory".equals(kind)) {
            try {
                Files.delete(path);
                return success(requested, kind, 1);
            } catch (DirectoryNotEmptyException e) {
                return ToolErrors.failure(
                        "DIRECTORY_NOT_EMPTY",
                        "Directory is not empty; recursive=true is required.");
            } catch (IOException | SecurityException e) {
                return ToolErrors.failure("IO_ERROR", "Cannot delete path: " + requested);
            }
        }

        List<Path> entries = preflight(path);
        int deleted = 0;
        for (int index = entries.size() - 1; index >= 0; index--) {
            Path entry = entries.get(index);
            try {
                Files.delete(entry);
                deleted++;
            } catch (java.nio.file.NoSuchFileException | DirectoryNotEmptyException e) {
                return ToolErrors.failure(
                        "TREE_CHANGED",
                        "Directory changed during deletion after "
                                + deleted
                                + " entries were deleted.");
            } catch (IOException | SecurityException e) {
                return ToolErrors.failure(
                        "IO_ERROR", "Deletion stopped after " + deleted + " entries: " + requested);
            }
        }
        return success(requested, kind, deleted);
    }

    private static @NonNull List<Path> preflight(@NonNull Path root) {
        List<Path> entries = new ArrayList<>();
        Instant started = Instant.now();
        try {
            Files.walkFileTree(
                    root,
                    new SimpleFileVisitor<>() {
                        @Override
                        public @NonNull FileVisitResult preVisitDirectory(
                                @NonNull Path dir, @NonNull BasicFileAttributes attrs) {
                            add(dir);
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public @NonNull FileVisitResult visitFile(
                                @NonNull Path file, @NonNull BasicFileAttributes attrs) {
                            add(file);
                            return FileVisitResult.CONTINUE;
                        }

                        private void add(@NonNull Path entry) {
                            entries.add(entry);
                            if (entries.size() > MAX_ENTRIES
                                    || Duration.between(started, Instant.now())
                                                    .compareTo(MAX_DURATION)
                                            > 0) {
                                throw new DeleteLimitExceeded();
                            }
                        }
                    });
        } catch (DeleteLimitExceeded e) {
            return ToolErrors.failure(
                    "DELETE_LIMIT_EXCEEDED",
                    "Directory deletion preflight exceeded its safety limit.");
        } catch (IOException e) {
            return ToolErrors.failure(
                    "SAFE_DELETE_UNAVAILABLE",
                    "Cannot inspect the complete directory before deleting it.");
        }
        return entries;
    }

    private static void verifyNoProtectedDescendant(@NonNull Path path) {
        var context = ToolCallContextHolder.get();
        if (context == null) return;
        for (Path protectedPath : context.executionPermit().protectedPaths()) {
            if (protectedPath.startsWith(path)) {
                ToolErrors.refused("DESCENDANT_REFUSED", "Path contains a protected descendant.");
            }
        }
    }

    private static @NonNull String success(@NonNull String path, @NonNull String kind, int count) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "deleted");
        result.put("path", path);
        result.put("kind", kind);
        result.put("entriesDeleted", count);
        return ToolJson.object(result);
    }

    private static @NonNull String requestedPath(@NonNull String fallback) {
        var context = ToolCallContextHolder.get();
        var authorized = context == null ? null : context.executionPermit().path("absolutePath");
        return authorized == null ? fallback : authorized.requestedPath();
    }

    private static @NonNull String kind(@NonNull Path path) {
        if (Files.isSymbolicLink(path)) return "link";
        if (Files.isDirectory(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return "directory";
        return "file";
    }

    @SuppressWarnings("serial")
    private static final class DeleteLimitExceeded extends RuntimeException {}
}
