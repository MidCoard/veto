package top.focess.veto.llm.egress;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import top.focess.veto.llm.core.ProviderType;
import top.focess.veto.llm.credential.CredentialResolver;

/**
 * Default egress: make the HTTPS call in-place. The real credential is resolved from the vault
 * Vault and handed to the SDK, which calls the provider directly. Active unless {@code
 * veto.llm.egress.mode=proxy}.
 */
@Component
@ConditionalOnProperty(name = "veto.llm.egress.mode", havingValue = "direct", matchIfMissing = true)
public class DirectEgress implements LlmEgress {
    private final CredentialResolver credentialResolver;

    /**
     * Constructs a new DirectEgress with the specified credential resolver.
     *
     * @param credentialResolver the resolver to use for fetching API keys
     */
    public DirectEgress(CredentialResolver credentialResolver) {
        this.credentialResolver = credentialResolver;
    }

    @Override
    public EgressEndpoint resolve(
            ProviderType providerType, String defaultBaseUrl, String credentialKey) {
        String apiKey = credentialResolver.resolve(providerType, credentialKey);
        return new EgressEndpoint(defaultBaseUrl, apiKey);
    }
}
