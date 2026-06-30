package top.focess.veto.llm.credential;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import top.focess.veto.llm.core.ProviderType;
import top.focess.veto.llm.exceptions.LlmAuthException;
import top.focess.veto.vault.CredentialVault;
import top.focess.veto.vault.SecureStore;

/**
 * Resolves the API key for a request from the vault {@link CredentialVault}, keyed by {@code
 * VetoRequest.credentialKey}. Credentials are looked up at call time so they never live inside the
 * request object or the audit trail.
 */
@Service
public class CredentialResolver {
    private final @NonNull CredentialVault vault;

    /**
     * Constructs a new CredentialResolver with the specified credential vault.
     *
     * @param vault the vault used to retrieve credentials
     */
    public
    @NonNull
    CredentialResolver(@NonNull CredentialVault vault) {
        this.vault = vault;
    }

    /**
     * Resolves the API key for the given provider type and credential key.
     *
     * @param providerType the target provider type
     * @param credentialKey the key used to look up the credential in the vault
     * @return the resolved API key
     * @throws LlmAuthException if the credential key is missing or no credential is found
     */
    public @Nullable String resolve(
            @NonNull ProviderType providerType, @NonNull String credentialKey) {
        if (credentialKey == null || credentialKey.isEmpty()) {
            throw new LlmAuthException("Credential key is missing for provider " + providerType);
        }
        try {
            return vault.retrieve(credentialKey)
                    .orElseThrow(
                            () ->
                                    new LlmAuthException(
                                            "No credential registered in Vault under key: "
                                                    + credentialKey));
        } catch (SecureStore.VaultLockedException e) {
            throw new LlmAuthException("Vault is locked — authenticate before making LLM calls", e);
        }
    }
}
