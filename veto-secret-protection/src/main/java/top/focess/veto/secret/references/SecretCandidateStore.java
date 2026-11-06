package top.focess.veto.secret.references;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.plugin.Scope;

/** Bounded transient captures. No raw-value lookup is exposed to tools or model callers. */
public final class SecretCandidateStore {

    private final @NonNull Set<UUID> closedUsers = new HashSet<>();
    private final @NonNull Set<SecretSessionIdentity> retiredSessions = new HashSet<>();
    private static final @NonNull Pattern REFERENCE = Pattern.compile("\\[SECRET_REF:([^]]+)]");

    /** Lifecycle state of a captured candidate. */
    public enum State {
        AVAILABLE,
        IMPORTED,
        EXPIRED,
        DISCARDED
    }

    /** Immutable metadata describing a captured candidate; never contains the secret value. */
    public record Descriptor(
            @NonNull String reference,
            @NonNull String sourceId,
            int start,
            int end,
            @NonNull String category,
            @NonNull Instant expiresAt,
            @NonNull State state) {}

    /** Result of a capture pass: the masked text and the candidates it produced. */
    public record Capture(@NonNull String text, @NonNull List<Descriptor> candidates) {}

    /** Confirmation returned after a candidate is imported into the userId's vault. */
    public record ImportReceipt(
            @NonNull String credentialRef, @NonNull String service, @NonNull String label) {}

    private final @NonNull Map<String, SecretCandidate> entries = new LinkedHashMap<>();
    private final @NonNull Clock clock;
    private final @NonNull Duration ttl;
    private final int maxCount;
    private final int maxBytes;
    private final int maxValueBytes;

    /** Creates a store with production defaults: UTC clock, 30-minute TTL, and standard limits. */
    public SecretCandidateStore() {
        this(Clock.systemUTC(), Duration.ofMinutes(30), 64, 256 * 1024, 16 * 1024);
    }

    /**
     * Creates a store with explicit retention limits.
     *
     * @param clock the time source driving expiry
     * @param ttl how long a captured value stays available before expiring
     * @param maxCount the maximum number of retained live candidates
     * @param maxBytes the maximum total UTF-8 bytes of retained secret values
     * @param maxValueBytes the maximum UTF-8 bytes of a single secret value
     */
    public SecretCandidateStore(
            @NonNull Clock clock,
            @NonNull Duration ttl,
            int maxCount,
            int maxBytes,
            int maxValueBytes) {
        if (ttl.isNegative() || ttl.isZero() || maxCount < 1 || maxBytes < 1 || maxValueBytes < 1)
            throw new IllegalArgumentException("Invalid candidate store limits");
        this.clock = clock;
        this.ttl = ttl;
        this.maxCount = maxCount;
        this.maxBytes = maxBytes;
        this.maxValueBytes = maxValueBytes;
    }

    /** Captures an exact source value selected by an admitted Secret Agent tool call. */
    public synchronized @NonNull Capture captureExact(
            Scope.@NonNull AgentScope scope,
            @NonNull String sourceId,
            @NonNull String input,
            @NonNull String value) {
        return captureExact(scope, sourceId, input, value, false);
    }

    /** File captures retain line boundaries so source locations remain meaningful. */
    public synchronized @NonNull Capture captureExact(
            Scope.@NonNull AgentScope scope,
            @NonNull String sourceId,
            @NonNull String input,
            @NonNull String value,
            boolean preserveLines) {
        if (value.isEmpty() || value.length() > maxValueBytes || !input.contains(value))
            throw new IllegalArgumentException("Credential must be an exact nonempty source value");
        List<Span> matches = new ArrayList<>();
        for (int start = input.indexOf(value);
                start >= 0;
                start = input.indexOf(value, start + value.length()))
            matches.add(new Span(start, start + value.length(), "slm-detected"));
        return captureMatches(scope, sourceId, input, preserveLines, matches);
    }

    private @NonNull Capture captureMatches(
            Scope.@NonNull AgentScope scope,
            @NonNull String sourceId,
            @NonNull String input,
            boolean preserveLines,
            @NonNull List<Span> detected) {
        if (closedUsers.contains(scope.userId())
                || retiredSessions.contains(
                        new SecretSessionIdentity(scope.userId(), scope.session())))
            throw new IllegalStateException("Secret capture is unavailable");
        expire();
        var references = referenceRanges(scope, input);
        List<Span> matches = new ArrayList<>(detected);
        Map<String, SecretCandidate> known = new LinkedHashMap<>();
        for (SecretCandidate entry : entries.values()) {
            String value = entry.value;
            if (!entry.scope.equals(scope) || value == null) continue;
            known.put(value, entry);
            for (int index = input.indexOf(value);
                    index >= 0;
                    index = input.indexOf(value, index + value.length()))
                matches.add(new Span(index, index + value.length(), entry.descriptor.category()));
        }
        matches.sort(
                Comparator.comparingInt(Span::start)
                        .thenComparing(Comparator.comparingInt(Span::end).reversed()));
        Map<String, SecretCandidate> added = new LinkedHashMap<>();
        List<Descriptor> captured = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        int end = 0;
        long bytes =
                entries.values().stream()
                        .filter(entry -> entry.value != null)
                        .mapToLong(entry -> entry.bytes)
                        .sum();
        long count = entries.values().stream().filter(entry -> entry.value != null).count();
        for (var match : matches) {
            if (match.start() < end) continue;
            boolean insideReference = false;
            for (int[] range : references) {
                if (match.start() >= range[1] || match.end() <= range[0]) continue;
                if (match.start() < range[0] || match.end() > range[1])
                    throw new IllegalStateException("Secret capture overlaps a reference");
                insideReference = true;
            }
            if (insideReference) continue;
            String value = input.substring(match.start(), match.end());
            SecretCandidate entry = known.get(value);
            if (entry == null) {
                int size = value.getBytes(StandardCharsets.UTF_8).length;
                if (size > maxValueBytes || ++count > maxCount || (bytes += size) > maxBytes)
                    throw new IllegalStateException("Secret candidate capacity exceeded");
                String reference = "s_" + UUID.randomUUID().toString().replace("-", "");
                entry =
                        new SecretCandidate(
                                scope,
                                new Descriptor(
                                        reference,
                                        sourceId,
                                        match.start(),
                                        match.end(),
                                        match.category(),
                                        clock.instant().plus(ttl),
                                        State.AVAILABLE),
                                value,
                                size);
                known.put(value, entry);
                added.put(reference, entry);
            }
            text.append(input, end, match.start())
                    .append("[SECRET_REF:")
                    .append(entry.descriptor.reference())
                    .append(']');
            if (preserveLines) {
                for (int position = match.start(); position < match.end(); position++) {
                    char character = input.charAt(position);
                    if (character == '\r' || character == '\n') text.append(character);
                }
            }
            captured.add(entry.descriptor);
            end = match.end();
        }
        text.append(input, end, input.length());
        entries.putAll(added);
        trimTombstones();
        return new Capture(text.toString(), List.copyOf(captured));
    }

    private record Span(int start, int end, @NonNull String category) {}

    private @NonNull List<int[]> referenceRanges(
            Scope.@NonNull AgentScope scope, @NonNull String input) {
        List<int[]> ranges = new ArrayList<>();
        var matcher = REFERENCE.matcher(input);
        while (matcher.find()) {
            String reference = matcher.group(1);
            SecretCandidate entry = reference == null ? null : resolveReference(scope, reference);
            if (entry == null || !entry.scope.equals(scope) || entry.value == null)
                throw new IllegalStateException("Secret reference is unavailable");
            ranges.add(new int[] {matcher.start(), matcher.end()});
        }
        for (var entry : entries.values()) {
            var receipt = entry.imported;
            if (receipt == null || !entry.scope.equals(scope)) continue;
            String reference = receipt.credentialRef();
            for (int index = input.indexOf(reference);
                    index >= 0;
                    index = input.indexOf(reference, index + reference.length()))
                ranges.add(new int[] {index, index + reference.length()});
        }
        ranges.sort(Comparator.comparingInt(range -> range[0]));
        return ranges;
    }

    /** Returns the descriptor for a reference within the scope, or empty if unknown or expired. */
    public synchronized @NonNull Optional<Descriptor> describe(
            Scope.@NonNull AgentScope scope, @NonNull String reference) {
        expire();
        SecretCandidate entry = entries.get(reference);
        return entry == null || !entry.scope.equals(scope)
                ? Optional.empty()
                : Optional.of(entry.descriptor);
    }

    /** Returns an owned live value; callers enforce browser or tool disclosure authorization. */
    public synchronized @NonNull Optional<String> reveal(
            Scope.@NonNull AgentScope scope, @NonNull String reference) {
        expire();
        SecretCandidate entry = resolveReference(scope, reference);
        if (closedUsers.contains(scope.userId())
                || retiredSessions.contains(
                        new SecretSessionIdentity(scope.userId(), scope.session()))
                || entry == null
                || !entry.scope.equals(scope)) return Optional.empty();
        return Optional.ofNullable(entry.value);
    }

    private SecretCandidate resolveReference(
            Scope.@NonNull AgentScope scope, @NonNull String reference) {
        var candidate = entries.get(reference);
        if (candidate != null && candidate.scope.equals(scope)) return candidate;
        return entries.values().stream()
                .filter(
                        entry -> {
                            var receipt = entry.imported;
                            return entry.scope.equals(scope)
                                    && receipt != null
                                    && receipt.credentialRef().equals(reference);
                        })
                .findFirst()
                .orElse(null);
    }

    /** Storage transition only. The trusted caller must validate the execution permit first. */
    public synchronized void recordImport(
            Scope.@NonNull AgentScope scope,
            @NonNull String reference,
            @NonNull ImportReceipt receipt) {
        expire();
        var entry = entries.get(reference);
        if (entry == null || !entry.scope.equals(scope) || entry.value == null)
            throw new IllegalStateException("Secret reference is unavailable");
        if (entry.imported != null && !entry.imported.equals(receipt))
            throw new IllegalStateException("Credential receipt changed");
        entry.imported = receipt;
        var d = entry.descriptor;
        entry.descriptor =
                new Descriptor(
                        d.reference(),
                        d.sourceId(),
                        d.start(),
                        d.end(),
                        d.category(),
                        d.expiresAt(),
                        State.IMPORTED);
    }

    /** Discards the captured values of one userId/session; does not block future captures. */
    public synchronized void discardSession(@NonNull UUID userId, @NonNull String session) {
        entries.values().stream()
                .filter(
                        entry ->
                                entry.scope.userId().equals(userId)
                                        && entry.scope.session().equals(session))
                .forEach(entry -> entry.discard(State.DISCARDED));
    }

    /**
     * Discards the captured values of one userId/session/agent scope; does not block future
     * captures.
     */
    public synchronized void discardAgent(Scope.@NonNull AgentScope scope) {
        entries.values().stream()
                .filter(entry -> entry.scope.equals(scope))
                .forEach(entry -> entry.discard(State.DISCARDED));
    }

    /** Discards the captured values of one userId; does not block future captures. */
    public synchronized void discardUser(@NonNull UUID userId) {
        entries.values().stream()
                .filter(entry -> entry.scope.userId().equals(userId))
                .forEach(entry -> entry.discard(State.DISCARDED));
    }

    /**
     * Discards an userId's captures and blocks further capture, reveal, and import for that userId.
     */
    public synchronized void closeUser(@NonNull UUID userId) {
        closedUsers.add(userId);
        discardUser(userId);
    }

    /** Lifts a prior {@link #closeUser} block, allowing capture for the userId again. */
    public synchronized void openUser(@NonNull UUID userId) {
        closedUsers.remove(userId);
    }

    /** Discards a session's captures and blocks further capture, reveal, and import for it. */
    public synchronized void retireSession(@NonNull UUID userId, @NonNull String session) {
        retiredSessions.add(new SecretSessionIdentity(userId, session));
        discardSession(userId, session);
    }

    /** Release all captured secret material when the owning plugin stops. */
    public synchronized void clear() {
        entries.values().forEach(entry -> entry.discard(State.DISCARDED));
        entries.clear();
        closedUsers.clear();
        retiredSessions.clear();
    }

    /** Discards candidates past their TTL and trims discarded tombstones above the size bound. */
    public synchronized void expire() {
        Instant now = clock.instant();
        entries.values().stream()
                .filter(entry -> entry.value != null && !now.isBefore(entry.descriptor.expiresAt()))
                .forEach(entry -> entry.discard(State.EXPIRED));
        trimTombstones();
    }

    private void trimTombstones() {
        var iterator = entries.values().iterator();
        while (entries.size() > Math.max(256, maxCount) && iterator.hasNext()) {
            if (iterator.next().value == null) iterator.remove();
        }
    }

    /**
     * Captured secret state. Access and discard require the owning SecretCandidateStore monitor.
     */
    private static final class SecretCandidate {
        final Scope.@NonNull AgentScope scope;
        @NonNull Descriptor descriptor;
        String value;
        ImportReceipt imported;
        final int bytes;

        SecretCandidate(
                Scope.@NonNull AgentScope scope,
                @NonNull Descriptor descriptor,
                @NonNull String value,
                int bytes) {
            this.scope = scope;
            this.descriptor = descriptor;
            this.value = value;
            this.bytes = bytes;
        }

        void discard(@NonNull State state) {
            value = null;
            var d = descriptor;
            descriptor =
                    new Descriptor(
                            d.reference(),
                            d.sourceId(),
                            d.start(),
                            d.end(),
                            d.category(),
                            d.expiresAt(),
                            state);
        }
    }

    /** User and session identity used to prevent capture after session retirement. */
    private record SecretSessionIdentity(@NonNull UUID userId, @NonNull String session) {}
}
