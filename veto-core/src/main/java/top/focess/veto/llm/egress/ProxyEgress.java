package top.focess.veto.llm.egress;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import top.focess.veto.llm.core.ProviderType;
import top.focess.veto.llm.exceptions.ModelCapabilityException;

/**
 * Opt-in egress: route the call through a separate credential-injecting broker process. The engine
 * points the SDK at the broker's base URL and presents only a low-value internal token; the broker
 * holds the real provider secret, enforces host/scope/spend policy, injects the {@code
 * Authorization} header, and forwards to the provider.
 *
 * <p>This keeps the real credential entirely out of the engine JVM, defending against in-process
 * memory readers and malicious plugins. Active when {@code veto.llm.egress.mode=proxy}.
 */
@Component
@ConditionalOnProperty(name = "veto.llm.egress.mode", havingValue = "proxy")
public class ProxyEgress implements LlmEgress {
    private final LlmEgressProperties properties;

    /**
     * Constructs a new ProxyEgress with the specified egress properties.
     *
     * @param properties the configuration properties for LLM egress
     */
    public ProxyEgress(LlmEgressProperties properties) {
        this.properties = properties;
    }

    @Override
    public EgressEndpoint resolve(
            ProviderType providerType, String defaultBaseUrl, String credentialKey) {
        String brokerBaseUrl = properties.baseUrlFor(providerType);
        if (brokerBaseUrl == null || brokerBaseUrl.isEmpty()) {
            throw new ModelCapabilityException(
                    "Egress proxy mode is enabled but no broker base URL is configured for "
                            + providerType);
        }
        // The credential reference is encoded by the broker route; the engine only proves it is the
        // engine via the internal token. The real secret is never resolved or held here.
        return new EgressEndpoint(brokerBaseUrl, properties.getInternalToken());
    }
}
