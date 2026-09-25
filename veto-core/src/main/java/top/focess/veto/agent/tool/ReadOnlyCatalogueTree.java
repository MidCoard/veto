package top.focess.veto.agent.tool;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.resources.CatalogueTree;

/** Generic bounded catalogue snapshots; never traverses links or returns host paths. */
public final class ReadOnlyCatalogueTree implements CatalogueTree {
    private static final int MAX_FILES = 256;
    private static final int MAX_VISITED = 4096;
    private static final long MAX_BYTES = 262144;
    private final @NonNull List<@NonNull Path> roots;
    private final @NonNull Runnable authorize;

    /**
     * Creates a catalogue over the given roots. The {@code authorize} callback runs before every
     * access and must throw once the presentation grant has expired.
     */
    public ReadOnlyCatalogueTree(@NonNull List<@NonNull Path> roots, @NonNull Runnable authorize) {
        this.roots = roots.stream().map(path -> path.toAbsolutePath().normalize()).toList();
        this.authorize = authorize;
    }

    /** Stable content-agnostic identity of the root set, released only after authorization. */
    public @NonNull String identity() {
        authorize.run();
        return hash(roots.toString());
    }

    /**
     * Lists matching catalogue files under a relative {@code directory}, refusing traversal outside
     * the roots, symbolic links, and entries beyond the file-count or size limits.
     */
    public @NonNull List<CatalogueTree.@NonNull File> files(
            @NonNull String directory, @NonNull String fileName) throws IOException {
        authorize.run();
        if (directory.contains("..") || !fileName.matches("[A-Za-z0-9._-]{1,128}"))
            throw new SecurityException("Invalid relative catalogue request");
        List<CatalogueTree.File> result = new ArrayList<>();
        for (Path root : roots) {
            Path start = root.resolve(directory).normalize();
            if (!start.startsWith(root)) continue;
            if (!Files.exists(start, LinkOption.NOFOLLOW_LINKS)) continue;
            verify(root, start);
            int visited = 0;
            try (var walk = Files.walk(start, 8)) {
                var iterator = walk.iterator();
                while (iterator.hasNext()) {
                    Path path = iterator.next();
                    if (++visited > MAX_VISITED)
                        throw new IOException("Catalogue exceeds traversal limit");
                    Path name = path.getFileName();
                    if (name == null
                            || !name.toString().equals(fileName)
                            || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) continue;
                    verify(root, path);
                    var attributes = attributes(path);
                    if (attributes.size() > MAX_BYTES)
                        throw new IOException("Catalogue file exceeds size limit");
                    if (result.size() == MAX_FILES)
                        throw new IOException("Catalogue exceeds file limit");
                    result.add(new Entry(root, path, attributes));
                }
            }
        }
        return List.copyOf(result);
    }

    private static void verify(@NonNull Path root, @NonNull Path path) throws IOException {
        if (!path.startsWith(root) || !path.toRealPath().startsWith(root.toRealPath()))
            throw new SecurityException("Catalogue path escaped root");
        for (Path current = path;
                current != null && current.startsWith(root);
                current = current.getParent())
            if (Files.isSymbolicLink(current))
                throw new SecurityException("Catalogue links are not allowed");
    }

    private static @NonNull BasicFileAttributes attributes(@NonNull Path path) throws IOException {
        return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    }

    private final class Entry implements CatalogueTree.File {
        private final @NonNull Path root;
        private final @NonNull Path path;
        private final @NonNull BasicFileAttributes selected;

        Entry(@NonNull Path root, @NonNull Path path, @NonNull BasicFileAttributes selected) {
            this.root = root;
            this.path = path;
            this.selected = selected;
        }

        public @NonNull String identity() {
            return hash(
                    System.getProperty("os.name", "").startsWith("Windows")
                            ? path.toString().toLowerCase(Locale.ROOT)
                            : path.toString());
        }

        public @NonNull String relativePath() {
            return root.relativize(path).toString().replace('\\', '/');
        }

        public @NonNull String read() throws IOException {
            authorize.run();
            verify(root, path);
            var before = attributes(path);
            if (!same(selected, before) || before.size() > MAX_BYTES)
                throw new SecurityException("Catalogue file changed");
            byte[] bytes;
            try (var stream = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
                bytes = stream.readNBytes((int) MAX_BYTES + 1);
            }
            verify(root, path);
            if (bytes.length > MAX_BYTES || !same(before, attributes(path)))
                throw new SecurityException("Catalogue file changed");
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private static boolean same(@NonNull BasicFileAttributes a, @NonNull BasicFileAttributes b) {
        return Objects.equals(a.fileKey(), b.fileKey())
                && a.creationTime().equals(b.creationTime())
                && a.lastModifiedTime().equals(b.lastModifiedTime())
                && a.size() == b.size();
    }

    /** SHA-256 hex digest of the given text. */
    public static @NonNull String hash(@NonNull String text) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
