package top.focess.veto.llm.egress;

import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import top.focess.veto.llm.core.ProviderType;

/**
 * Configuration for LLM egress. Bound from {@code veto.llm.egress.*}.
 *
 * <pre>
 * veto:
 *   llm:
 *     egress:
 *       mode: direct            # or: proxy
 *       internal-token: ...      # token the engine presents to the broker (proxy mode)
 *       proxy-base-urls:
 *         openai: http://127.0.0.1:9000/openai
 *         anthropic: http://127.0.0.1:9000/anthropic
 * </pre>
 */
@Component
@ConfigurationProperties(prefix = "veto.llm.egress")
public class LlmEgressProperties {
    /**
     * Egress strategy: {@code direct} (in-place HTTP, default) or {@code proxy} (broker process).
     */
    private String mode = "direct";

    /** Token the engine presents to the broker so the broker can authenticate the caller. */
    private String internalToken;

    /** Per-provider broker base URLs used in proxy mode. */
    private Map<ProviderType, String> proxyBaseUrls = new HashMap<>();

    /**
     * Returns the egress mode.
     *
     * @return the egress mode (direct or proxy)
     */
    public String getMode() {
        return mode;
    }

    /**
     * Sets the egress mode.
     *
     * @param mode the egress mode to set
     */
    public void setMode(String mode) {
        this.mode = mode;
    }

    /**
     * Returns the internal token used for proxy authentication.
     *
     * @return the internal token
     */
    public String getInternalToken() {
        return internalToken;
    }

    /**
     * Sets the internal token used for proxy authentication.
     *
     * @param internalToken the internal token to set
     */
    public void setInternalToken(String internalToken) {
        this.internalToken = internalToken;
    }

    /**
     * Returns the map of per-provider broker base URLs.
     *
     * @return the proxy base URLs map
     */
    public Map<ProviderType, String> getProxyBaseUrls() {
        return proxyBaseUrls;
    }

    /**
     * Sets the map of per-provider broker base URLs.
     *
     * @param proxyBaseUrls the proxy base URLs map to set
     */
    public void setProxyBaseUrls(Map<ProviderType, String> proxyBaseUrls) {
        this.proxyBaseUrls = proxyBaseUrls;
    }

    /**
     * Resolves the broker base URL for the given provider type.
     *
     * @param providerType the provider type to look up
     * @return the configured broker base URL, or null if not found
     */
    public String baseUrlFor(ProviderType providerType) {
        return proxyBaseUrls.get(providerType);
    }
}
