package top.focess.veto.llm.credential;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import top.focess.veto.llm.core.ProviderType;
import top.focess.veto.llm.exceptions.CredentialException;
import top.focess.veto.vault.KeysteadVault;

/**
 * Resolves the API key for a request from the {@link KeysteadVault}, keyed by {@code
 * VetoRequest.credentialKey}. The credential is a keystead {@code SECURE_NOTE} titled by the key;
 * it is looked up at call time so it never lives inside the request object or the audit trail.
 */
@Service
public class CredentialResolver {
    private final @NonNull KeysteadVault vault;

    /**
     * Constructs a new CredentialResolver with the specified keystead vault.
     *
     * @param vault the vault used to retrieve credentials
     */
    public CredentialResolver(@NonNull KeysteadVault vault) {
        this.vault = vault;
    }

    /**
     * Resolves the API key for the given provider type and credential key.
     *
     * @param providerType the target provider type
     * @param credentialKey the key (secure-note title) used to look up the credential in the vault
     * @return the resolved API key
     * @throws CredentialException if the credential key is missing, no credential is found, or the
     *     vault is locked
     */
    public @Nullable String resolve(
            @NonNull ProviderType providerType, @NonNull String credentialKey) {
        if (credentialKey.isEmpty()) {
            throw new CredentialException("Credential key is missing for provider " + providerType);
        }
        try {
            return vault.readNoteBody(credentialKey)
                    .orElseThrow(
                            () ->
                                    new CredentialException(
                                            "No credential registered in Vault under key: "
                                                    + credentialKey));
        } catch (KeysteadVault.VaultLockedException e) {
            throw new CredentialException(
                    "Vault is locked - authenticate before making LLM calls", e);
        }
    }
}
