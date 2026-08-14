package top.focess.veto.security;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
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
}
