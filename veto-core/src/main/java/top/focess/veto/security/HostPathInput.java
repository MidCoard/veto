package top.focess.veto.security;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import org.jspecify.annotations.NonNull;

/** Validates untrusted host-path text before it reaches filesystem operations. */
public final class HostPathInput {

    private HostPathInput() {}

    /** Parses an absolute path and rejects inputs whose meaning changes during normalization. */
    public static @NonNull Path absoluteNormalized(
            @NonNull String input, @NonNull String fieldName) {
        Path supplied = parse(input, fieldName);
        if (!supplied.isAbsolute()) {
            throw new IllegalArgumentException(fieldName + " must be an absolute path");
        }
        Path normalized = supplied.normalize();
        if (!normalized.equals(supplied)) {
            throw new IllegalArgumentException(fieldName + " must not contain '.' or '..'");
        }
        return normalized;
    }

    /** Parses a path and resolves relative input against the process working directory. */
    public static @NonNull Path normalized(@NonNull String input, @NonNull String fieldName) {
        return parse(input, fieldName).toAbsolutePath().normalize();
    }

    private static @NonNull Path parse(@NonNull String input, @NonNull String fieldName) {
        if (input.isBlank() || input.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(fieldName + " is empty or contains a null byte");
        }
        try {
            // Parser boundary; callers enforce operation-specific roots.
            //noinspection tainting
            return Path.of(input);
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException(fieldName + " is not a valid host path", e);
        }
    }

    /** Resolves existing segments so a symlink cannot disguise an out-of-scope future child. */
    public static @NonNull Path canonicalForCreation(
            @NonNull Path path, @NonNull String fieldName) {
        Path absolute = path.toAbsolutePath().normalize();
        Deque<Path> missing = new ArrayDeque<>();
        Path existing = absolute;
        while (existing != null && !Files.exists(existing)) {
            Path name = existing.getFileName();
            if (name != null) {
                missing.addFirst(name);
            }
            existing = existing.getParent();
        }
        if (existing == null) {
            throw new IllegalArgumentException(fieldName + " has no existing filesystem ancestor");
        }
        try {
            Path resolved = existing.toRealPath();
            for (Path segment : missing) {
                resolved = resolved.resolve(segment);
            }
            return resolved.normalize();
        } catch (IOException e) {
            throw new IllegalArgumentException(fieldName + " cannot be canonicalized", e);
        }
    }
}
