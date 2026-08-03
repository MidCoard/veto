package top.focess.veto.model.tier;

import org.jspecify.annotations.NonNull;

/**
 * Whether a credential key exists in a user's vault. The model-tier service uses this to validate
 * that a {@code credKey} set via {@code /modeltier set <profile> <tier> credKey <value>} names a
 * real stored credential before persisting it, so a typo (or a forward-reference to a credential
 * the user has not stored yet) is rejected at set-time rather than surfacing as a cryptic error at
 * LLM-call time.
 *
 * <p>This is a port owned by the service layer (dependency inversion): {@link
 * DefaultModelTierService} depends on this abstraction, not on the concrete keystead vault, which
 * keeps its JPA slice tests free of vault infrastructure. The keystead-backed implementation lives
 * in the vault package.
 */
public interface CredentialExistenceChecker {

    /**
     * Whether the credential {@code credentialKey} exists in {@code username}'s vault.
     *
     * @param username the vault owner
     * @param credentialKey the credential key (title) to look up
     * @return true if a credential with that key is stored in the user's vault
     */
    boolean exists(@NonNull String username, @NonNull String credentialKey);
}
