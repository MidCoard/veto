package top.focess.veto.contract;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import org.jspecify.annotations.NonNull;

/** Immutable product version carried as a canonical string in Hello and Welcome. */
public record Version(int major, int minor, int patch, String preRelease, String build) {
    /** Sentinel for "no version reported". */
    public static final @NonNull Version UNKNOWN = new Version(0, 0, 0, "unknown", null);

    /**
     * Parses a version string leniently.
     *
     * <p>Accepts an optional leading {@code v}/{@code V}, one to three numeric core components
     * ({@code major}, {@code major.minor}, {@code major.minor.patch}), an optional pre-release
     * suffix (the substring after the first {@code -}), and optional build metadata (after {@code
     * +}).
     *
     * @param raw the version text (e.g. {@code "1.0.0-SNAPSHOT"}, {@code "v2.1"}, {@code
     *     "1.0.0-beta+exp.sha"})
     * @return the parsed version; never {@code null}
     * @throws IllegalArgumentException if the text is not a recognizable version
     */
    @JsonCreator
    public static @NonNull Version parse(@NonNull String raw) {
        String s = raw.trim();
        if (s.isEmpty()) {
            throw new IllegalArgumentException("empty version");
        }
        if (s.charAt(0) == 'v' || s.charAt(0) == 'V') {
            s = s.substring(1);
        }
        String build = null;
        int plus = s.indexOf('+');
        if (plus >= 0) {
            build = s.substring(plus + 1);
            s = s.substring(0, plus);
        }
        String preRelease = null;
        int dash = s.indexOf('-');
        if (dash >= 0) {
            preRelease = s.substring(dash + 1);
            s = s.substring(0, dash);
        }
        String[] parts = s.split("\\.", -1);
        if (parts.length > 3) {
            throw new IllegalArgumentException("invalid version: " + raw);
        }
        int major = Integer.parseInt(parts[0].trim());
        int minor = parts.length >= 2 ? Integer.parseInt(parts[1].trim()) : 0;
        int patch = parts.length == 3 ? Integer.parseInt(parts[2].trim()) : 0;
        return new Version(major, minor, patch, preRelease, build);
    }

    /** Canonical string form: {@code MAJOR.MINOR.PATCH[-preRelease][+build]}. */
    @JsonValue
    @Override
    public @NonNull String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(major).append('.').append(minor).append('.').append(patch);
        if (preRelease != null) {
            sb.append('-').append(preRelease);
        }
        if (build != null) {
            sb.append('+').append(build);
        }
        return sb.toString();
    }
}
