package top.focess.veto.llm.egress;

import top.focess.veto.llm.core.ProviderType;

/**
 * Strategy that decides how an LLM request leaves this process. The default ({@code DirectEgress})
 * makes the HTTPS call in-place with a Vault-resolved key; an opt-in ({@code ProxyEgress}) routes
 * through a separate credential-injecting broker process so the real secret never lives here.
 *
 * <p>Selected by the {@code veto.llm.egress.mode} property ({@code direct} by default).
 */
public interface LlmEgress {
    /**
     * Resolve the transport target for a call.
     *
     * @param providerType the target provider
     * @param defaultBaseUrl the provider's own base URL (may be {@code null} for the SDK default)
     * @param credentialKey the Vault reference for the credential (never the secret value)
     */
    EgressEndpoint resolve(ProviderType providerType, String defaultBaseUrl, String credentialKey);
}
