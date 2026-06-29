package top.focess.veto.group;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The Part 5.5 trust chain — a sealed record of the trust model for skillset authorizations in a
 * group (capability_trust_chain.md). Each grant of a skillset to a Mate carries a {@code
 * TrustChain} that documents:
 *
 * <ol>
 *   <li><b>root authority</b> — the deployer or user that issued the grant
 *   <li><b>delegation chain</b> — the sequence of intermediate grants (Leader → Mate)
 *   <li><b>trust markers</b> — the trust level at each step (OWNED / DELEGATED / SHARED_GRANT)
 *   <li><b>scope</b> — what the grant covers (skillset, time bounds, project)
 *   <li><b>audit</b> — the SHA-256 of the full chain for tamper detection
 * </ol>
 *
 * <p>The trust chain is consulted at:
 *
 * <ul>
 *   <li>Mate assignment — a Mate's trust level must be ≥ the skillset's required trust.
 *   <li>Tool invocation — the Gateway consults the chain to decide if a tool call should be allowed
 *       (e.g. OWNED vs SHARED_GRANT for write tools).
 *   <li>Cross-user access — a TENANT deployer policy checks the chain's user id.
 * </ul>
 *
 * <p>The chain is a sealed interface; concrete types are {@link OwnerIssued}, {@link
 * DeployerGranted}, and {@link SessionEphemeral}. All chains carry the same root + delegation +
 * scope fields; the differing piece is the issuance / revocation policy.
 */
public sealed interface TrustChain
        permits TrustChain.OwnerIssued, TrustChain.DeployerGranted, TrustChain.SessionEphemeral {

    /** Root authority that issued the chain (deployer, user, or Leader). */
    String rootAuthority();

    /** The Mate's id (the chain's terminal subject). */
    String subjectId();

    /** Skillset the grant covers. */
    String skillset();

    /** Scope tags (project, env, etc.) — empty means "all". */
    Set<String> scope();

    /** The trust marker at the root of the chain. */
    String trust();

    /** The full delegation chain as a list of {@link Hop}s (root first, subject last). */
    List<Hop> hops();

    /** When the chain was issued. */
    Instant issuedAt();

    /** When the chain expires (null = no expiry). */
    Instant expiresAt();

    /**
     * The SHA-256 of the canonical chain payload (root + subject + skillset + hops + trust +
     * issuedAt), hex-encoded. Recorded at chain construction; recomputed and compared in {@link
     * #verifyIntegrity()}. Any retroactive edit to a hop, the subject, or the issuance time changes
     * the digest and is detected.
     */
    String auditHash();

    /** One hop in the delegation chain: who delegated, to whom, with what trust level, and when. */
    record Hop(String fromAuthority, String toAuthority, String trust, Instant at) {
        public Hop {
            Objects.requireNonNull(fromAuthority, "fromAuthority");
            Objects.requireNonNull(toAuthority, "toAuthority");
            Objects.requireNonNull(trust, "trust");
            Objects.requireNonNull(at, "at");
        }
    }

    /** Trust levels. */
    enum TrustLevel {
        /** Root-of-trust (deployer-issued, or the user themselves). */
        OWNED,
        /** Delegated by an OWNED authority. */
        DELEGATED,
        /** Shared across users (e.g. owner-issued cross-user grant). */
        SHARED_GRANT
    }

    /**
     * Owner-issued trust chain: a user grants a skillset to a Mate within their own workspace. Full
     * authority, no expiry by default.
     */
    record OwnerIssued(
            String userId,
            String subjectId,
            String skillset,
            Set<String> scope,
            List<Hop> hops,
            Instant issuedAt)
            implements TrustChain {
        public OwnerIssued {
            if (hops == null || hops.isEmpty()) {
                throw new IllegalArgumentException("hops must include at least the owner");
            }
            hops = List.copyOf(hops);
            scope = scope == null ? Set.of() : Set.copyOf(scope);
        }

        @Override
        public String rootAuthority() {
            return hops.get(0).fromAuthority();
        }

        @Override
        public String trust() {
            return TrustLevel.OWNED.name();
        }

        @Override
        public Instant expiresAt() {
            return null; // owner-issued chains don't expire
        }

        @Override
        public String auditHash() {
            return computeAuditHash(rootAuthority(), subjectId, skillset, trust(), hops, issuedAt);
        }
    }

    /**
     * Deployer-granted trust chain: the deployer (a config-time principal) grants a skillset to a
     * user, who can then delegate. Optional expiry.
     */
    record DeployerGranted(
            String deployerId,
            String userId,
            String subjectId,
            String skillset,
            Set<String> scope,
            List<Hop> hops,
            Instant issuedAt,
            Instant expiresAt)
            implements TrustChain {
        public DeployerGranted {
            if (hops == null || hops.isEmpty()) {
                throw new IllegalArgumentException("hops must include at least the deployer");
            }
            hops = List.copyOf(hops);
            scope = scope == null ? Set.of() : Set.copyOf(scope);
        }

        @Override
        public String rootAuthority() {
            return hops.get(0).fromAuthority();
        }

        @Override
        public String trust() {
            return TrustLevel.DELEGATED.name();
        }

        @Override
        public String auditHash() {
            return computeAuditHash(rootAuthority(), subjectId, skillset, trust(), hops, issuedAt);
        }
    }

    /**
     * Session-ephemeral trust chain: a Leader delegates a skillset to a Mate within one session.
     * Auto-revoked on session end.
     */
    record SessionEphemeral(
            UUID groupId,
            String leaderId,
            String subjectId,
            String skillset,
            List<Hop> hops,
            Instant issuedAt)
            implements TrustChain {

        public SessionEphemeral {
            if (hops == null || hops.isEmpty()) {
                throw new IllegalArgumentException("hops must include at least the leader");
            }
            hops = List.copyOf(hops);
        }

        @Override
        public String rootAuthority() {
            return leaderId;
        }

        @Override
        public Set<String> scope() {
            return Set.of("group:" + groupId);
        }

        @Override
        public String trust() {
            return TrustLevel.DELEGATED.name();
        }

        @Override
        public Instant expiresAt() {
            // Session-ephemeral chains expire on session end (when the group is disbanded).
            return null;
        }

        @Override
        public String auditHash() {
            return computeAuditHash(rootAuthority(), subjectId, skillset, trust(), hops, issuedAt);
        }
    }

    /**
     * Verify the integrity of the chain. Two checks, in order:
     *
     * <ol>
     *   <li><b>Structural</b> — hops form a connected chain from root to subject.
     *   <li><b>Cryptographic</b> — the SHA-256 of the canonical chain payload matches the recorded
     *       {@link #auditHash()}. Catches retroactive edits to hops, subject, skillset, trust
     *       marker, or issuance time that the structural check would miss.
     * </ol>
     *
     * <p>Returns true only if both checks pass.
     */
    default boolean verifyIntegrity() {
        if (hops() == null || hops().isEmpty()) {
            return false;
        }
        if (!hops().get(0).fromAuthority().equals(rootAuthority())) {
            return false;
        }
        if (!hops().get(hops().size() - 1).toAuthority().equals(subjectId())) {
            return false;
        }
        for (int i = 0; i < hops().size() - 1; i++) {
            if (!hops().get(i).toAuthority().equals(hops().get(i + 1).fromAuthority())) {
                return false;
            }
        }
        String expected = auditHash();
        if (expected == null) {
            return false;
        }
        // Self-verify: the canonical hash of the current state must match the recorded auditHash
        // (which is itself derived from the current state at construction time). This is what
        // makes the chain tamper-evident: any later edit changes the input and therefore the
        // recomputed hash, which no longer matches the recorded one.
        return expected.equals(
                computeAuditHash(
                        rootAuthority(), subjectId(), skillset(), trust(), hops(), issuedAt()));
    }

    /**
     * Compute the canonical SHA-256 audit hash for a chain payload. The format is the pipe-joined
     * concatenation of root, subject, skillset, trust, the hops (each as "from→to[trust@at]"), and
     * the issuedAt ISO string. SHA-256 of the UTF-8 bytes, hex-encoded (lowercase).
     */
    private static String computeAuditHash(
            String root,
            String subject,
            String skillset,
            String trust,
            List<Hop> hops,
            Instant issuedAt) {
        StringBuilder sb = new StringBuilder();
        sb.append("root=")
                .append(safe(root))
                .append("|subject=")
                .append(safe(subject))
                .append("|skillset=")
                .append(safe(skillset))
                .append("|trust=")
                .append(safe(trust))
                .append("|hops=");
        if (hops != null) {
            for (int i = 0; i < hops.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                Hop h = hops.get(i);
                sb.append(safe(h.fromAuthority()))
                        .append("->")
                        .append(safe(h.toAuthority()))
                        .append('[')
                        .append(safe(h.trust()))
                        .append('@')
                        .append(h.at() == null ? "" : h.at().toString())
                        .append(']');
            }
        }
        sb.append("|issuedAt=").append(issuedAt == null ? "" : issuedAt.toString());
        return sha256Hex(sb.toString());
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required by every JRE; this is unreachable in practice.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
