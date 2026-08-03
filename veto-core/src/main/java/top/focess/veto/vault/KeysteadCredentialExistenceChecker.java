package top.focess.veto.vault;

import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import top.focess.veto.model.tier.CredentialExistenceChecker;

/**
 * Keystead-backed {@link CredentialExistenceChecker}. Delegates to {@link KeysteadVault#hasNote} so
 * the model-tier service stays decoupled from vault internals. Lives in the vault package next to
 * the concrete vault it wraps.
 */
@Component
public class KeysteadCredentialExistenceChecker implements CredentialExistenceChecker {

    private final @NonNull KeysteadVault vault;

    public KeysteadCredentialExistenceChecker(@NonNull KeysteadVault vault) {
        this.vault = vault;
    }

    @Override
    public boolean exists(@NonNull String username, @NonNull String credentialKey) {
        return vault.hasNote(username, credentialKey);
    }
}
