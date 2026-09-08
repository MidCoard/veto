package top.focess.veto.agent.capability;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.intercept.ToolExecutionPermit;
import top.focess.veto.agent.intercept.ToolExecutionPermit.FileIdentity;
import top.focess.veto.agent.screening.DeployerPolicy;
import top.focess.veto.agent.tool.ToolCapability;
import top.focess.veto.agent.tool.ToolErrors;

/** Private resource binding shared by read and write handles. */
final class WorkspaceFileAccess {
    static final long MAX_BYTES = 16L * 1024 * 1024;
    private static final int MAX_CHILDREN = 50_000;
    private final @NonNull ToolExecutionPermit permit;
    private final @NonNull Path path;
    private final boolean writable;
    private final boolean protectedEntry;
    private final @NonNull Map<@NonNull Path, @NonNull FileIdentity> ancestors;
    private @NonNull FileIdentity identity;

    private WorkspaceFileAccess(
            @NonNull ToolExecutionPermit permit,
            @NonNull Path path,
            boolean writable,
            boolean protectedEntry,
            @NonNull FileIdentity identity,
            @NonNull Map<@NonNull Path, @NonNull FileIdentity> ancestors) {
        this.permit = permit;
        this.path = path;
        this.writable = writable;
        this.protectedEntry = protectedEntry;
        this.identity = identity;
        this.ancestors = ancestors;
    }

    static @NonNull WorkspaceFileAccess resolve(
            @NonNull ToolExecutionPermit permit, @NonNull String requested, boolean writable)
            throws IOException {
        requireLifetime(permit, writable);
        for (var authorized : permit.filesystemPaths().values()) {
            Path host = authorized.hostPath();
            if (host == null
                    || !(authorized.requestedPath().equals(requested)
                            || host.toString().equals(requested))) {
                continue;
            }
            if ((permit.deployerPolicy() == DeployerPolicy.SANDBOXED
                            || permit.deployerPolicy() == DeployerPolicy.TENANT)
                    && !authorized.inScope()) {
                throw new SecurityException("Path is outside the configured policy scope");
            }
            Path normalized = host.toAbsolutePath().normalize();
            refuseProtected(permit, normalized, writable);
            Map<@NonNull Path, @NonNull FileIdentity> ancestors = new LinkedHashMap<>();
            for (Path parent = normalized.getParent();
                    parent != null;
                    parent = parent.getParent()) {
                refuseLink(parent);
                ancestors.put(parent, FileIdentity.capture(parent));
            }
            Path parent = normalized.getParent();
            if (parent != null
                    && !authorized.parentIdentity().sameObject(FileIdentity.capture(parent))) {
                ToolErrors.failure("TREE_CHANGED", "File parent changed after authorization.");
            }
            WorkspaceFileAccess result =
                    new WorkspaceFileAccess(
                            permit, normalized, writable, false, authorized.identity(), ancestors);
            result.verify();
            return result;
        }
        throw new SecurityException("Path was not authorized for this invocation");
    }

    @NonNull String name() {
        requireLifetime(permit, writable);
        Path name = path.getFileName();
        return name == null ? path.toString() : name.toString();
    }

    @NonNull String kind() throws IOException {
        if (protectedEntry) {
            requireLifetime(permit, writable);
            return "unavailable";
        }
        verify();
        if (isLink(path)) {
            return "symbolic_link";
        }
        if (identity.state() == FileIdentity.State.MISSING) {
            throw new NoSuchFileException(path.toString());
        }
        return identity.directory() ? "directory" : "file";
    }

    long size() throws IOException {
        if (protectedEntry) {
            requireLifetime(permit, writable);
            return 0;
        }
        verify();
        return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS)
                .size();
    }

    @NonNull InputStream openRead() throws IOException {
        verify();
        refuseLink(path);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Not a regular file");
        }
        if (size() > MAX_BYTES) {
            return ToolErrors.failure("FILE_TOO_LARGE", "File exceeds 16 MiB (16,777,216 bytes)");
        }
        InputStream stream =
                Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        try {
            verify();
            return new WorkspaceInputStream(this, stream);
        } catch (RuntimeException | IOException exception) {
            stream.close();
            throw exception;
        }
    }

    @NonNull List<@NonNull WorkspaceFileAccess> children() throws IOException {
        verify();
        refuseLink(path);
        if (!identity.directory()) {
            throw new IOException("Not a directory");
        }
        List<@NonNull WorkspaceFileAccess> result = new ArrayList<>();
        long started = System.nanoTime();
        try (var entries = Files.newDirectoryStream(path)) {
            for (Path child : entries) {
                verify();
                if (result.size() >= MAX_CHILDREN
                        || System.nanoTime() - started > 10_000_000_000L) {
                    throw new IOException("Directory enumeration exceeded its safety limit");
                }
                Path normalized = child.toAbsolutePath().normalize();
                if (!path.equals(normalized.getParent())) {
                    throw new SecurityException("Invalid directory entry");
                }
                boolean denied = isProtected(permit, normalized);
                if (writable) {
                    refuseProtected(permit, normalized, true);
                }
                Map<@NonNull Path, @NonNull FileIdentity> parents = new LinkedHashMap<>(ancestors);
                parents.put(path, identity);
                result.add(
                        new WorkspaceFileAccess(
                                permit,
                                normalized,
                                writable,
                                denied,
                                denied
                                        ? FileIdentity.unavailable()
                                        : FileIdentity.capture(normalized),
                                parents));
            }
        }
        verify();
        return List.copyOf(result);
    }

    @NonNull OutputStream openWrite(boolean replace) throws IOException {
        verifyWritable();
        refuseLink(path);
        if (!replace && identity.state() == FileIdentity.State.PRESENT) {
            throw new FileAlreadyExistsException(path.toString());
        }
        if (identity.state() == FileIdentity.State.PRESENT
                && !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Not a regular file");
        }
        return new WorkspaceOutputStream(this, replace);
    }

    void publish(byte @NonNull [] bytes, boolean replace) throws IOException {
        verifyWritable();
        refuseLink(path);
        if (bytes.length > MAX_BYTES) {
            throw new IOException("Content exceeds 16 MiB (16,777,216 bytes)");
        }
        Path parent = path.getParent();
        if (parent == null) {
            throw new IOException("File has no parent directory");
        }
        createParents();
        verifyWritable();
        Path temporary = parent.resolve(".veto-write-" + UUID.randomUUID() + ".tmp");
        boolean created = false;
        boolean moved = false;
        FileIdentity createdIdentity;
        try {
            try (OutputStream staging =
                    Files.newOutputStream(
                            temporary,
                            StandardOpenOption.CREATE_NEW,
                            StandardOpenOption.WRITE,
                            LinkOption.NOFOLLOW_LINKS)) {
                created = true;
                createdIdentity = FileIdentity.capture(temporary);
                staging.write(bytes);
            }
            // Capture after our own write: providers without file keys use size and mtime.
            FileIdentity temporaryIdentity = verifyStagedFile(temporary, createdIdentity, bytes);
            verifyWritable();
            refuseLink(path);
            if (!temporaryIdentity.sameObject(FileIdentity.capture(temporary))) {
                throw new SecurityException("Staged file changed before publication");
            }
            if (replace) {
                Files.move(
                        temporary,
                        path,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.move(temporary, path);
            }
            moved = true;
            identity = FileIdentity.capture(path);
        } finally {
            if (created && !moved) {
                verifyAncestors();
                // Delete only the generated directory entry; never follow its target.
                Files.deleteIfExists(temporary);
            }
        }
    }

    private static @NonNull FileIdentity verifyStagedFile(
            @NonNull Path temporary, @NonNull FileIdentity createdIdentity, byte @NonNull [] bytes)
            throws IOException {
        refuseLink(temporary);
        FileIdentity current = FileIdentity.capture(temporary);
        if (current.state() != FileIdentity.State.PRESENT
                || current.directory()
                || current.symbolicLink()
                || current.size() != bytes.length) {
            throw new SecurityException("Staged file changed before publication");
        }
        if (!createdIdentity.fileKey().isEmpty() || !current.fileKey().isEmpty()) {
            if (!createdIdentity.fileKey().equals(current.fileKey())) {
                throw new SecurityException("Staged file changed before publication");
            }
        } else {
            // Without a provider file key, verify the exact bounded content before publication.
            try (InputStream staged =
                    Files.newInputStream(
                            temporary, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                if (!Arrays.equals(bytes, staged.readNBytes(bytes.length + 1))) {
                    throw new SecurityException("Staged content changed before publication");
                }
            }
        }
        if (!current.sameObject(FileIdentity.capture(temporary))) {
            throw new SecurityException("Staged file changed before publication");
        }
        return current;
    }

    void delete() throws IOException {
        verifyWritable();
        Files.delete(path);
        identity = FileIdentity.missing();
    }

    void moveTo(@NonNull WorkspaceFileAccess destination) throws IOException {
        verifyWritable();
        destination.verifyWritable();
        if (destination.permit != permit) {
            throw new SecurityException("Destination belongs to another invocation");
        }
        if (destination.path.startsWith(path) && !destination.path.equals(path)) {
            throw new IOException("A directory cannot be moved inside itself");
        }
        Path parent = destination.path.getParent();
        if (parent == null || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Destination parent is not a directory");
        }
        if (Files.exists(destination.path, LinkOption.NOFOLLOW_LINKS)) {
            throw new FileAlreadyExistsException(destination.path.toString());
        }
        Path sourceStorePath = isLink(path) ? path.getParent() : path;
        if (sourceStorePath == null
                || !Files.getFileStore(sourceStorePath).equals(Files.getFileStore(parent))) {
            ToolErrors.failure(
                    "CROSS_FILESYSTEM_MOVE",
                    "Source and destination are on different filesystems.");
        }
        // Inspect all entries before a directory mutation; never descend through links.
        List<@NonNull WorkspaceFileAccess> tree = new ArrayList<>();
        tree.add(this);
        long started = System.nanoTime();
        for (int index = 0; index < tree.size(); index++) {
            WorkspaceFileAccess entry = tree.get(index);
            if ("directory".equals(entry.kind())) {
                tree.addAll(entry.children());
            }
            if (tree.size() > MAX_CHILDREN || System.nanoTime() - started > 10_000_000_000L) {
                throw new IOException("Move preflight exceeded its safety limit");
            }
        }
        for (var entry : tree) {
            entry.verifyWritable();
        }
        destination.verifyWritable();
        Files.move(path, destination.path);
        identity = FileIdentity.missing();
        destination.identity = FileIdentity.capture(destination.path);
    }

    void verify() throws IOException {
        requireLifetime(permit, writable);
        if (protectedEntry) {
            throw new SecurityException("Protected file cannot be accessed");
        }
        refuseProtected(permit, path, writable);
        verifyAncestors();
        if (!identity.sameObject(FileIdentity.capture(path))) {
            ToolErrors.failure("TREE_CHANGED", "File changed after authorization.");
        }
    }

    private void verifyWritable() throws IOException {
        if (!writable) {
            throw new SecurityException("File is read only");
        }
        verify();
    }

    private void verifyAncestors() throws IOException {
        for (var ancestor : ancestors.entrySet()) {
            refuseLink(ancestor.getKey());
            if (!ancestor.getValue().sameObject(FileIdentity.capture(ancestor.getKey()))) {
                ToolErrors.failure("TREE_CHANGED", "File ancestor changed after authorization.");
            }
        }
    }

    private void createParents() throws IOException {
        List<@NonNull Path> missing = new ArrayList<>();
        for (Path parent = path.getParent();
                parent != null && !Files.exists(parent, LinkOption.NOFOLLOW_LINKS);
                parent = parent.getParent()) {
            missing.add(parent);
        }
        for (int index = missing.size() - 1; index >= 0; index--) {
            verifyWritable();
            Path directory = missing.get(index);
            refuseProtected(permit, directory, false);
            Files.createDirectory(directory);
            ancestors.put(directory, FileIdentity.capture(directory));
        }
    }

    private static void requireLifetime(@NonNull ToolExecutionPermit permit, boolean writable) {
        var context =
                CapabilityAccess.require(
                        writable ? ToolCapability.WORKSPACE_WRITE : ToolCapability.WORKSPACE_READ);
        if (context.executionPermit() != permit) {
            throw new SecurityException("File handle does not belong to this invocation");
        }
    }

    private static boolean isProtected(@NonNull ToolExecutionPermit permit, @NonNull Path path) {
        return permit.protectedPaths().stream().anyMatch(path::startsWith);
    }

    private static void refuseProtected(
            @NonNull ToolExecutionPermit permit, @NonNull Path path, boolean tree) {
        if (isProtected(permit, path)) {
            ToolErrors.refused("PATH_PROTECTED", "Protected path cannot be accessed.");
        }
        if (tree
                && permit.protectedPaths().stream()
                        .anyMatch(protectedPath -> protectedPath.startsWith(path))) {
            ToolErrors.refused("DESCENDANT_REFUSED", "Path contains a protected descendant.");
        }
    }

    private static void refuseLink(@NonNull Path path) throws IOException {
        if (isLink(path)) {
            ToolErrors.failure(
                    "UNSAFE_LINK", "Symbolic links and reparse points cannot be followed.");
        }
    }

    private static boolean isLink(@NonNull Path path) throws IOException {
        if (Files.isSymbolicLink(path)) {
            return true;
        }
        try {
            return Boolean.TRUE.equals(
                    Files.getAttribute(path, "dos:reparsePoint", LinkOption.NOFOLLOW_LINKS));
        } catch (NoSuchFileException
                | UnsupportedOperationException
                | IllegalArgumentException exception) {
            return false;
        }
    }
}
