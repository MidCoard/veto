package top.focess.veto.llm.credential;

import org.springframework.stereotype.Service;
import top.focess.veto.llm.core.ProviderType;
import top.focess.veto.llm.exceptions.LlmAuthException;
import top.focess.veto.vault.CredentialVault;

/**
 * Resolves the API key for a request from the C8 {@link CredentialVault}, keyed by {@code
 * VetoRequest.credentialKey()}. Credentials are looked up at call time so they never live inside
 * the request object or the audit trail.
 */
@Service
public class CredentialResolver {
    private final CredentialVault vault;

    /**
     * Constructs a new CredentialResolver with the specified credential vault.
     *
     * @param vault the vault used to retrieve credentials
     */
    public CredentialResolver(CredentialVault vault) {
        this.vault = vault;
    }

    /**
     * Resolves the API key for the given provider type and credential key.
     *
     * @param providerType  the target provider type
     * @param credentialKey the key used to look up the credential in the vault
     * @return the resolved API key
     * @throws LlmAuthException if the credential key is missing or no credential is found
     */
    public String resolve(ProviderType providerType, String credentialKey) {
        if (credentialKey == null || credentialKey.isBlank()) {
            throw new LlmAuthException("Credential key is missing for provider " + providerType);
        }
        return vault
                .retrieve(credentialKey)
                .orElseThrow(
                        () ->
                                new LlmAuthException(
                                        "No credential registered in Vault under key: " + credentialKey));
    }
}
