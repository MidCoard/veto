package top.focess.veto.contract;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * A semantic version with structured, comparable meaning.
 *
 * <p>Unlike a bare {@code String}, a {@code Version} exposes its {@link #major()}, {@link
 * #minor()}, {@link #patch()}, {@link #preRelease()} and {@link #build()} components and implements
 * semver precedence via {@link #compareTo(Version)}. It is parsed leniently from a variety of
 * syntaxes - {@code "1.0.0-SNAPSHOT"}, {@code "1.2"}, {@code "v3.0.0-rc.1"}, {@code
 * "1.0.0+build.42"} - and reformatted canonically as {@code
 * MAJOR.MINOR.PATCH[-preRelease][+build]}.
 *
 * <p>On the IPC wire it serializes to that canonical string (Jackson {@code @JsonValue} /
 * {@code @JsonCreator}), so the JSON stays a plain string while Java code handles a typed value.
 *
 * <p>Null is never a valid {@code Version}; peers that genuinely cannot report a version use the
 * {@link #UNKNOWN} sentinel.
 */
public record Version(
        int major, int minor, int patch, @Nullable String preRelease, @Nullable String build)
        implements Comparable<@NonNull Version> {

    /** Non-null sentinel for "no version reported"; distinguishable via {@link #isUnknown()}. */
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
        if (parts.length == 0 || parts.length > 3) {
            throw new IllegalArgumentException("invalid version: " + raw);
        }
        int major = parseCore(parts[0], raw);
        int minor = parts.length >= 2 ? parseCore(parts[1], raw) : 0;
        int patch = parts.length >= 3 ? parseCore(parts[2], raw) : 0;
        return new Version(major, minor, patch, preRelease, build);
    }

    private static int parseCore(@NonNull String text, @NonNull String raw) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "invalid numeric version component in '" + raw + "'", e);
        }
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

    /** Whether this is the {@link #UNKNOWN} sentinel. */
    public boolean isUnknown() {
        return major == 0
                && minor == 0
                && patch == 0
                && "unknown".equals(preRelease)
                && build == null;
    }

    /** Whether this is a stable release (no pre-release identifier). */
    public boolean isStable() {
        return preRelease == null;
    }

    /** Whether this is a snapshot pre-release ({@code -SNAPSHOT}). */
    public boolean isSnapshot() {
        return "SNAPSHOT".equalsIgnoreCase(preRelease);
    }

    /**
     * Semver precedence: major, then minor, then patch; a version without a pre-release outranks
     * the same version with one; pre-release identifiers compare per semver (numeric &lt;
     * alphanumeric, fewer identifiers &lt; more). Build metadata is ignored.
     */
    @Override
    public int compareTo(@NonNull Version o) {
        int c = Integer.compare(major, o.major);
        if (c != 0) return c;
        c = Integer.compare(minor, o.minor);
        if (c != 0) return c;
        c = Integer.compare(patch, o.patch);
        if (c != 0) return c;
        if (preRelease == null && o.preRelease == null) return 0;
        if (preRelease == null) return 1; // stable outranks pre-release
        if (o.preRelease == null) return -1;
        return comparePreRelease(preRelease, o.preRelease);
    }

    private static int comparePreRelease(@NonNull String a, @NonNull String b) {
        String[] as = a.split("\\.", -1);
        String[] bs = b.split("\\.", -1);
        int n = Math.min(as.length, bs.length);
        for (int i = 0; i < n; i++) {
            int c = compareIdentifier(as[i], bs[i]);
            if (c != 0) return c;
        }
        return Integer.compare(as.length, bs.length); // fewer identifiers = lower precedence
    }

    private static int compareIdentifier(@NonNull String a, @NonNull String b) {
        Integer ai = tryNumeric(a);
        Integer bi = tryNumeric(b);
        if (ai != null && bi != null) return ai.compareTo(bi);
        if (ai != null) return -1; // numeric < non-numeric
        if (bi != null) return 1;
        return a.compareTo(b); // lexical
    }

    private static @Nullable Integer tryNumeric(@NonNull String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
