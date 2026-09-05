package top.focess.veto.agent.capability;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileStore;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.intercept.ToolExecutionPermit;
import top.focess.veto.agent.intercept.ToolExecutionPermit.FileIdentity;
import top.focess.veto.agent.tool.ToolErrors;
import top.focess.veto.agent.tool.ToolJson;

/** The only low-level filesystem implementation behind {@link WorkspaceWriteCapability}. */
final class WorkspaceWriteCapabilityImpl implements WorkspaceWriteCapability {

    private static final long TEXT_MAX_BYTES = 16L * 1024 * 1024;
    private static final String TEXT_MAX_SIZE = "16 MiB (16,777,216 bytes)";
    private static final int TREE_MAX_ENTRIES = 50_000;
    private static final Duration TREE_MAX_DURATION = Duration.ofSeconds(10);

    private final @NonNull ToolExecutionPermit permit;

    WorkspaceWriteCapabilityImpl(@NonNull ToolExecutionPermit permit) {
        this.permit = permit;
    }

    @Override
    public @NonNull String writeText(
            @NonNull String pathArgument, @NonNull String content, boolean overwrite) {
        ToolExecutionPermit.AuthorizedPath authorized = authorizedPath(pathArgument);
        Path target = requiredHostPath(authorized);
        refuseProtectedTarget(target);
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > TEXT_MAX_BYTES) {
            return ToolErrors.failure("Content exceeds " + TEXT_MAX_SIZE);
        }
        if (!overwrite && Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            return ToolErrors.failure(
                    "File exists and overwrite=false: " + authorized.requestedPath());
        }
        refuseLinkTarget(target, authorized.requestedPath());
        verifyTargetIdentity(authorized, target, "Write target changed after screening.");
        verifyParentIdentity(authorized, target, "Write parent changed after screening.");
        try {
            atomicWrite(target, bytes, overwrite);
        } catch (FileAlreadyExistsException e) {
            return ToolErrors.failure(
                    "File exists and overwrite=false: " + authorized.requestedPath());
        } catch (IOException | SecurityException e) {
            return ToolErrors.failure(
                    "IO_ERROR", "Cannot write file: " + authorized.requestedPath());
        }
        return ToolJson.object(
                Map.of("status", "ok", "file", authorized.requestedPath(), "bytes", bytes.length));
    }

    @Override
    public @NonNull String replaceText(
            @NonNull String pathArgument,
            int startLine,
            int endLine,
            @NonNull String targetContent,
            @NonNull String replacementContent) {
        ToolExecutionPermit.AuthorizedPath authorized = authorizedPath(pathArgument);
        Path path = requiredHostPath(authorized);
        refuseProtectedTarget(path);
        refuseLinkTarget(path, authorized.requestedPath());
        final BasicFileAttributes attributes;
        try {
            attributes =
                    Files.readAttributes(
                            path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException e) {
            return ToolErrors.failure("Not a regular file: " + authorized.requestedPath());
        }
        if (!attributes.isRegularFile()) {
            return ToolErrors.failure("Not a regular file: " + authorized.requestedPath());
        }
        if (attributes.size() > TEXT_MAX_BYTES) {
            return ToolErrors.failure("File exceeds " + TEXT_MAX_SIZE);
        }
        if (startLine < 1 || endLine < startLine) {
            return ToolErrors.failure("Invalid line range");
        }
        if (targetContent.isEmpty()) {
            return ToolErrors.failure("targetContent must not be empty");
        }
        verifyTargetIdentity(authorized, path, "Edit target changed after screening.");
        final String content;
        try {
            content = Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return ToolErrors.failure(
                    "IO_ERROR", "Cannot read file: " + authorized.requestedPath());
        }
        int rangeStart = lineStart(content, startLine);
        int rangeEnd = lineEnd(content, endLine);
        if (rangeStart < 0 || rangeEnd < rangeStart) {
            return ToolErrors.failure("Line range outside file");
        }
        int index = content.indexOf(targetContent, rangeStart);
        if (index < 0 || index + targetContent.length() > rangeEnd) {
            return ToolErrors.failure("targetContent not found in selected range.");
        }
        int next = content.indexOf(targetContent, index + 1);
        if (next >= 0 && next + targetContent.length() <= rangeEnd) {
            return ToolErrors.failure("targetContent is not unique in selected range.");
        }
        String updated =
                content.substring(0, index)
                        + replacementContent
                        + content.substring(index + targetContent.length());
        byte[] updatedBytes = updated.getBytes(StandardCharsets.UTF_8);
        if (updatedBytes.length > TEXT_MAX_BYTES) {
            return ToolErrors.failure("Replacement exceeds " + TEXT_MAX_SIZE);
        }
        verifyTargetIdentity(authorized, path, "Edit target changed while it was being read.");
        try {
            atomicWrite(path, updatedBytes, true);
        } catch (IOException | SecurityException e) {
            return ToolErrors.failure(
                    "IO_ERROR", "Cannot update file: " + authorized.requestedPath());
        }
        return ToolJson.object(Map.of("status", "ok", "file", authorized.requestedPath()));
    }

    @Override
    public @NonNull String movePath(
            @NonNull String sourceArgument, @NonNull String destinationArgument) {
        ToolExecutionPermit.AuthorizedPath sourceAuthorization = authorizedPath(sourceArgument);
        ToolExecutionPermit.AuthorizedPath destinationAuthorization =
                authorizedPath(destinationArgument);
        Path source = requiredHostPath(sourceAuthorization);
        Path destination = requiredHostPath(destinationAuthorization);
        refuseProtectedTree(source);
        refuseProtectedTarget(destination);
        if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
            return ToolErrors.failure(
                    "SOURCE_NOT_FOUND",
                    "Source path not found: " + sourceAuthorization.requestedPath());
        }
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            return ToolErrors.failure(
                    "DESTINATION_EXISTS",
                    "Destination already exists: " + destinationAuthorization.requestedPath());
        }
        Path parent = destination.getParent();
        if (parent == null || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
            return ToolErrors.failure(
                    "INVALID_DESTINATION",
                    "Destination parent is not a directory: "
                            + destinationAuthorization.requestedPath());
        }
        if (Files.isSymbolicLink(parent) || isReparsePoint(parent)) {
            return ToolErrors.failure(
                    "UNSAFE_LINK", "Destination parent is a symbolic link or reparse point.");
        }
        if (destination.startsWith(source) && !destination.equals(source)) {
            return ToolErrors.failure(
                    "INVALID_DESTINATION", "A directory cannot be moved inside itself.");
        }
        List<TreeEntry> snapshot = List.of();
        if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS) && !isReparsePoint(source)) {
            snapshot = snapshotTree(source, "MOVE_LIMIT_EXCEEDED");
        }
        verifyUnchanged(snapshot, "Source directory changed after move preflight.");
        verifyTargetIdentity(sourceAuthorization, source, "Move source changed after screening.");
        verifyTargetIdentity(
                destinationAuthorization, destination, "Move destination changed after screening.");
        verifyParentIdentity(
                destinationAuthorization,
                destination,
                "Move destination parent changed after screening.");
        try {
            String kind = kind(source);
            Path sourceStorePath = source;
            if ("symbolic_link".equals(kind)) {
                Path sourceParent = source.getParent();
                if (sourceParent == null) {
                    throw new IOException("Link source has no parent directory");
                }
                // Moving a link moves its directory entry, not the target's filesystem object.
                sourceStorePath = sourceParent;
            }
            FileStore sourceStore = Files.getFileStore(sourceStorePath);
            FileStore destinationStore = Files.getFileStore(parent);
            if (!sourceStore.equals(destinationStore)) {
                return ToolErrors.failure(
                        "CROSS_FILESYSTEM_MOVE",
                        "Source and destination are on different filesystems.");
            }
            Files.move(source, destination);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "moved");
            result.put("source", sourceAuthorization.requestedPath());
            result.put("destination", destinationAuthorization.requestedPath());
            result.put("kind", kind);
            return ToolJson.object(result);
        } catch (FileAlreadyExistsException e) {
            return ToolErrors.failure(
                    "DESTINATION_EXISTS",
                    "Destination already exists: " + destinationAuthorization.requestedPath());
        } catch (IOException | SecurityException e) {
            return ToolErrors.failure(
                    "IO_ERROR", "Cannot move path: " + sourceAuthorization.requestedPath());
        }
    }

    @Override
    public @NonNull String deletePath(@NonNull String pathArgument, boolean recursive) {
        ToolExecutionPermit.AuthorizedPath authorized = authorizedPath(pathArgument);
        Path path = requiredHostPath(authorized);
        refuseProtectedTree(path);
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return ToolErrors.failure(
                    "PATH_NOT_FOUND", "Path not found: " + authorized.requestedPath());
        }
        String kind = kind(path);
        if (!recursive || !"directory".equals(kind)) {
            verifyTargetIdentity(authorized, path, "Delete target changed after screening.");
            try {
                Files.delete(path);
                return deleted(authorized.requestedPath(), kind, 1);
            } catch (DirectoryNotEmptyException e) {
                return ToolErrors.failure(
                        "DIRECTORY_NOT_EMPTY",
                        "Directory is not empty; recursive=true is required.");
            } catch (IOException | SecurityException e) {
                return ToolErrors.failure(
                        "IO_ERROR", "Cannot delete path: " + authorized.requestedPath());
            }
        }

        List<TreeEntry> entries = snapshotTree(path, "DELETE_LIMIT_EXCEEDED");
        verifyTargetIdentity(authorized, path, "Delete target changed after screening.");
        int deleted = 0;
        for (int index = entries.size() - 1; index >= 0; index--) {
            TreeEntry entry = entries.get(index);
            if (!entry.identity().sameObject(FileIdentity.capture(entry.path()))) {
                return ToolErrors.failure(
                        "TREE_CHANGED",
                        "Directory changed during deletion after "
                                + deleted
                                + " entries were deleted.");
            }
            try {
                Files.delete(entry.path());
                deleted++;
            } catch (NoSuchFileException | DirectoryNotEmptyException e) {
                return ToolErrors.failure(
                        "TREE_CHANGED",
                        "Directory changed during deletion after "
                                + deleted
                                + " entries were deleted.");
            } catch (IOException | SecurityException e) {
                return ToolErrors.failure(
                        "IO_ERROR",
                        "Deletion stopped after "
                                + deleted
                                + " entries: "
                                + authorized.requestedPath());
            }
        }
        return deleted(authorized.requestedPath(), kind, deleted);
    }

    private @NonNull List<@NonNull TreeEntry> snapshotTree(
            @NonNull Path root, @NonNull String limitCode) {
        List<TreeEntry> entries = new ArrayList<>();
        Instant started = Instant.now();
        boolean[] limitExceeded = {false};
        try {
            Files.walkFileTree(
                    root,
                    new SimpleFileVisitor<>() {
                        @Override
                        public @NonNull FileVisitResult preVisitDirectory(
                                @NonNull Path directory, @NonNull BasicFileAttributes attributes) {
                            refuseProtectedTarget(directory);
                            FileVisitResult result = add(directory, attributes);
                            if (result == FileVisitResult.CONTINUE
                                    && !directory.equals(root)
                                    && (attributes.isSymbolicLink()
                                            || Files.isSymbolicLink(directory)
                                            || isReparsePoint(directory))) {
                                return FileVisitResult.SKIP_SUBTREE;
                            }
                            return result;
                        }

                        @Override
                        public @NonNull FileVisitResult visitFile(
                                @NonNull Path file, @NonNull BasicFileAttributes attributes) {
                            refuseProtectedTarget(file);
                            return add(file, attributes);
                        }

                        private @NonNull FileVisitResult add(
                                @NonNull Path entry, @NonNull BasicFileAttributes attributes) {
                            if (entries.size() >= TREE_MAX_ENTRIES
                                    || Duration.between(started, Instant.now())
                                                    .compareTo(TREE_MAX_DURATION)
                                            > 0) {
                                limitExceeded[0] = true;
                                return FileVisitResult.TERMINATE;
                            }
                            entries.add(new TreeEntry(entry, identity(attributes)));
                            return FileVisitResult.CONTINUE;
                        }
                    });
        } catch (IOException e) {
            return ToolErrors.failure(
                    "SAFE_TREE_OPERATION_UNAVAILABLE",
                    "Cannot inspect the complete directory before changing it.");
        }
        if (limitExceeded[0]) {
            return ToolErrors.failure(limitCode, "Directory preflight exceeded its safety limit.");
        }
        return List.copyOf(entries);
    }

    private void refuseProtectedTarget(@NonNull Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (permit.protectedPaths().stream().anyMatch(normalized::startsWith)) {
            ToolErrors.refused("PATH_PROTECTED", "Protected path cannot be changed.");
        }
    }

    private void refuseProtectedTree(@NonNull Path root) {
        refuseProtectedTarget(root);
        Path normalized = root.toAbsolutePath().normalize();
        if (permit.protectedPaths().stream().anyMatch(path -> path.startsWith(normalized))) {
            ToolErrors.refused("DESCENDANT_REFUSED", "Path contains a protected descendant.");
        }
    }

    private static void refuseLinkTarget(@NonNull Path path, @NonNull String requestedPath) {
        if (Files.isSymbolicLink(path) || isReparsePoint(path)) {
            ToolErrors.failure(
                    "UNSAFE_LINK", "File is a symbolic link or reparse point: " + requestedPath);
        }
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

    private static void verifyTargetIdentity(
            ToolExecutionPermit.@NonNull AuthorizedPath authorized,
            @NonNull Path path,
            @NonNull String message) {
        if (!authorized.identity().sameObject(FileIdentity.capture(path))) {
            ToolErrors.failure("TREE_CHANGED", message);
        }
    }

    private static void verifyParentIdentity(
            ToolExecutionPermit.@NonNull AuthorizedPath authorized,
            @NonNull Path path,
            @NonNull String message) {
        Path parent = path.getParent();
        FileIdentity current =
                parent == null ? FileIdentity.missing() : FileIdentity.capture(parent);
        if (!authorized.parentIdentity().sameObject(current)) {
            ToolErrors.failure("TREE_CHANGED", message);
        }
    }

    private static void verifyUnchanged(
            @NonNull List<@NonNull TreeEntry> entries, @NonNull String message) {
        for (TreeEntry entry : entries) {
            if (!entry.identity().sameObject(FileIdentity.capture(entry.path()))) {
                ToolErrors.failure("TREE_CHANGED", message);
            }
        }
    }

    private static @NonNull FileIdentity identity(@NonNull BasicFileAttributes attributes) {
        return new FileIdentity(
                FileIdentity.State.PRESENT,
                Objects.toString(attributes.fileKey(), ""),
                attributes.size(),
                attributes.lastModifiedTime().toMillis(),
                attributes.isDirectory(),
                attributes.isSymbolicLink());
    }

    private static boolean isReparsePoint(@NonNull Path path) {
        try {
            Object value = Files.getAttribute(path, "dos:reparsePoint", LinkOption.NOFOLLOW_LINKS);
            return Boolean.TRUE.equals(value);
        } catch (IOException | UnsupportedOperationException | IllegalArgumentException e) {
            return false;
        }
    }

    private static @NonNull String kind(@NonNull Path path) {
        if (Files.isSymbolicLink(path) || isReparsePoint(path)) {
            return "symbolic_link";
        }
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            return "directory";
        }
        return "file";
    }

    private static @NonNull String deleted(@NonNull String path, @NonNull String kind, int count) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "deleted");
        result.put("path", path);
        result.put("kind", kind);
        result.put("entriesDeleted", count);
        return ToolJson.object(result);
    }

    private static int lineStart(@NonNull String content, int lineNumber) {
        if (lineNumber == 1) {
            return 0;
        }
        int currentLine = 1;
        for (int index = 0; index < content.length(); index++) {
            if (content.charAt(index) == '\n' && ++currentLine == lineNumber) {
                return index + 1;
            }
        }
        return -1;
    }

    private static int lineEnd(@NonNull String content, int lineNumber) {
        int start = lineStart(content, lineNumber);
        if (start < 0) {
            return -1;
        }
        int newline = content.indexOf('\n', start);
        return newline < 0 ? content.length() : newline + 1;
    }

    private static void atomicWrite(@NonNull Path target, byte @NonNull [] bytes, boolean overwrite)
            throws IOException {
        Path parent = target.toAbsolutePath().normalize().getParent();
        if (parent == null) {
            throw new IOException("Target has no parent directory: " + target);
        }
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, ".veto-write-", ".tmp");
        boolean moved = false;
        try {
            Files.write(temporary, bytes);
            if (overwrite) {
                try {
                    Files.move(
                            temporary,
                            target,
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } else {
                Files.move(temporary, target);
            }
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    private record TreeEntry(@NonNull Path path, @NonNull FileIdentity identity) {}
}
