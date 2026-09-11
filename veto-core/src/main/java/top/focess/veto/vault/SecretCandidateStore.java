package top.focess.veto.vault;

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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.capability.CapabilityAccess;
import top.focess.veto.agent.intercept.SecretMasker;
import top.focess.veto.agent.tool.ToolCapability;

/** Bounded transient captures. No raw-value lookup is exposed to tools or model callers. */
@Component
public final class SecretCandidateStore {
    /** Entry point for the screened native capability; no caller identity comes from model args. */
    public @NonNull ImportReceipt importApproved(
            @NonNull String reference,
            @NonNull String service,
            @NonNull String label,
            @NonNull KeysteadVault vault) {
        var context =
                CapabilityAccess.require(
                        ToolCapability.CREDENTIAL_IMPORT, "import_detected_credential");
        String owner = context.owner();
        var session = context.sessionId();
        if (owner == null
                || session == null
                || !context.executionPermit()
                        .call()
                        .args()
                        .equals(
                                Map.of(
                                        "secret_ref",
                                        reference,
                                        "service",
                                        service,
                                        "label",
                                        label))) {
            throw new SecurityException("Credential import does not match the authorized call");
        }
        return importOnce(
                new Scope(owner, session.toString(), context.agentId()),
                reference,
                service,
                label,
                vault);
    }

    private record SessionKey(@NonNull String owner, @NonNull String session) {}

    private final @NonNull Set<String> closedOwners = new HashSet<>();
    private final @NonNull Set<SessionKey> retiredSessions = new HashSet<>();
    private static final @NonNull Pattern REFERENCE = Pattern.compile("\\[SECRET_REF:([^\\]]+)\\]");

    public record Scope(@NonNull String owner, @NonNull String session, @NonNull String agent) {}

    public enum State {
        AVAILABLE,
        IMPORTED,
        EXPIRED,
        DISCARDED
    }

    public record Descriptor(
            @NonNull String reference,
            @NonNull String sourceId,
            int start,
            int end,
            @NonNull String category,
            @NonNull Instant expiresAt,
            @NonNull State state) {}

    public record Capture(@NonNull String text, @NonNull List<Descriptor> candidates) {}

    public record ImportReceipt(
            @NonNull String credentialRef, @NonNull String service, @NonNull String label) {}

    private static final class Entry {
        private final @NonNull Scope scope;
        private @NonNull Descriptor descriptor;
        private String value;
        private ImportReceipt imported;
        private final int bytes;

        private Entry(
                @NonNull Scope scope,
                @NonNull Descriptor descriptor,
                @NonNull String value,
                int bytes) {
            this.scope = scope;
            this.descriptor = descriptor;
            this.value = value;
            this.bytes = bytes;
        }

        private void discard(@NonNull State state) {
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

    private final @NonNull Map<String, Entry> entries = new LinkedHashMap<>();
    private final @NonNull Clock clock;
    private final @NonNull Duration ttl;
    private final int maxCount;
    private final int maxBytes;
    private final int maxValueBytes;

    public SecretCandidateStore() {
        this(Clock.systemUTC(), Duration.ofMinutes(30), 64, 256 * 1024, 16 * 1024);
    }

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

    public synchronized @NonNull Capture capture(
            @NonNull Scope scope, @NonNull String sourceId, @NonNull String input) {
        return capture(scope, sourceId, input, false);
    }

    /** Capture before rendering source lines while preserving CR, LF, and CRLF boundaries. */
    public synchronized @NonNull Capture captureFile(
            @NonNull Scope scope, @NonNull String sourceId, @NonNull String input) {
        return capture(scope, sourceId, input, true);
    }

    private @NonNull Capture capture(
            @NonNull Scope scope,
            @NonNull String sourceId,
            @NonNull String input,
            boolean preserveLines) {
        if (closedOwners.contains(scope.owner())
                || retiredSessions.contains(new SessionKey(scope.owner(), scope.session())))
            throw new IllegalStateException("Secret capture is unavailable");
        expire();
        List<int[]> references = new ArrayList<>();
        var referenceMatcher = REFERENCE.matcher(input);
        while (referenceMatcher.find()) {
            String reference = referenceMatcher.group(1);
            if (reference == null)
                throw new IllegalStateException("Secret reference is unavailable");
            Entry referenced = entries.get(reference);
            if (referenced == null || !referenced.scope.equals(scope) || referenced.value == null)
                throw new IllegalStateException("Secret reference is unavailable");
            references.add(new int[] {referenceMatcher.start(), referenceMatcher.end()});
        }
        List<SecretMasker.SecretMatch> matches = new ArrayList<>(SecretMasker.matches(input));
        Map<String, Entry> known = new LinkedHashMap<>();
        for (Entry entry : entries.values()) {
            String value = entry.value;
            if (!entry.scope.equals(scope) || value == null) continue;
            known.put(value, entry);
            for (int index = input.indexOf(value);
                    index >= 0;
                    index = input.indexOf(value, index + value.length()))
                matches.add(
                        new SecretMasker.SecretMatch(
                                index, index + value.length(), entry.descriptor.category()));
        }
        matches.sort(
                Comparator.comparingInt(SecretMasker.SecretMatch::start)
                        .thenComparing(
                                Comparator.comparingInt(SecretMasker.SecretMatch::end).reversed()));
        Map<String, Entry> added = new LinkedHashMap<>();
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
            Entry entry = known.get(value);
            if (entry == null) {
                int size = value.getBytes(StandardCharsets.UTF_8).length;
                if (size > maxValueBytes || ++count > maxCount || (bytes += size) > maxBytes)
                    throw new IllegalStateException("Secret candidate capacity exceeded");
                String reference = "s_" + UUID.randomUUID().toString().replace("-", "");
                entry =
                        new Entry(
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

    public record ReferenceSegment(@NonNull String text, boolean reference) {}

    /** Snapshot validated references; callers may mask plain segments without holding this lock. */
    public synchronized @NonNull List<ReferenceSegment> referenceSegments(
            @NonNull Scope scope, @NonNull String input) {
        expire();
        List<ReferenceSegment> segments = new ArrayList<>();
        var matcher = REFERENCE.matcher(input);
        int previous = 0;
        while (matcher.find()) {
            String reference = matcher.group(1);
            Entry entry = reference == null ? null : entries.get(reference);
            if (entry == null || !entry.scope.equals(scope) || entry.value == null)
                throw new IllegalStateException("Secret reference is unavailable");
            segments.add(new ReferenceSegment(input.substring(previous, matcher.start()), false));
            segments.add(
                    new ReferenceSegment(input.substring(matcher.start(), matcher.end()), true));
            previous = matcher.end();
        }
        segments.add(new ReferenceSegment(input.substring(previous), false));
        return List.copyOf(segments);
    }

    public synchronized @NonNull Optional<Descriptor> describe(
            @NonNull Scope scope, @NonNull String reference) {
        expire();
        Entry entry = entries.get(reference);
        return entry == null || !entry.scope.equals(scope)
                ? Optional.empty()
                : Optional.of(entry.descriptor);
    }

    /** Storage transition only. The trusted caller must validate the execution permit first. */
    synchronized @NonNull ImportReceipt importOnce(
            @NonNull Scope scope,
            @NonNull String reference,
            @NonNull String service,
            @NonNull String label,
            @NonNull KeysteadVault vault) {
        expire();
        Entry entry = entries.get(reference);
        if (closedOwners.contains(scope.owner())
                || retiredSessions.contains(new SessionKey(scope.owner(), scope.session()))
                || entry == null
                || !entry.scope.equals(scope)
                || entry.value == null)
            throw new IllegalStateException("Secret reference is unavailable");
        if (!service.equals("github")
                || label.isBlank()
                || label.length() > 80
                || !label.equals(label.trim()))
            throw new IllegalArgumentException("Invalid credential import binding");
        if (!vault.isUnlocked(scope.owner()))
            throw new IllegalStateException("Credential owner vault is locked");
        ImportReceipt previous = entry.imported;
        if (previous != null) {
            if (!previous.service().equals(service) || !previous.label().equals(label))
                throw new IllegalArgumentException("Credential import binding does not match");
            return previous;
        }
        String value = entry.value;
        if (value == null) throw new IllegalStateException("Secret reference is unavailable");
        ImportReceipt receipt;
        try {
            receipt =
                    new ImportReceipt(
                            vault.createImportedCredential(
                                    scope.owner(), reference, service, label, value),
                            service,
                            label);
        } catch (RuntimeException failure) {
            // Storage exceptions can originate in third-party code. Do not expose their payloads.
            throw new IllegalStateException("Credential import could not be saved");
        }
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
        return receipt;
    }

    public synchronized void discardSession(@NonNull String owner, @NonNull String session) {
        entries.values().stream()
                .filter(
                        entry ->
                                entry.scope.owner().equals(owner)
                                        && entry.scope.session().equals(session))
                .forEach(entry -> entry.discard(State.DISCARDED));
    }

    public synchronized void discardAgent(@NonNull Scope scope) {
        entries.values().stream()
                .filter(entry -> entry.scope.equals(scope))
                .forEach(entry -> entry.discard(State.DISCARDED));
    }

    public synchronized void discardOwner(@NonNull String owner) {
        entries.values().stream()
                .filter(entry -> entry.scope.owner().equals(owner))
                .forEach(entry -> entry.discard(State.DISCARDED));
    }

    public synchronized void closeOwner(@NonNull String owner) {
        closedOwners.add(owner);
        discardOwner(owner);
    }

    public synchronized void openOwner(@NonNull String owner) {
        closedOwners.remove(owner);
    }

    public synchronized void retireSession(@NonNull String owner, @NonNull String session) {
        retiredSessions.add(new SessionKey(owner, session));
        discardSession(owner, session);
    }

    @Scheduled(fixedDelay = 60000)
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
}
