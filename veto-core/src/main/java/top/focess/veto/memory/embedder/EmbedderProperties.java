package top.focess.veto.memory.embedder;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the hybrid embedder. When {@code veto.memory.embedder.provider} is set, a
 * {@link ProviderEmbedder} calls the provider's embeddings REST API; otherwise the {@link
 * HashEmbedder} local stub is used.
 *
 * <pre>
 * veto:
 *   memory:
 *     embedder:
 *       provider: openai            # or gemini; absent -> HashEmbedder
 *       model: text-embedding-3-small
 *       dimension: 1536             # must match the model; sizes the pgvector column
 *       base-url: https://api.openai.com   # optional; defaults per provider
 *       credential-key: openai-embeddings  # vault SECURE_NOTE title holding the API key
 * </pre>
 */
@ConfigurationProperties("veto.memory.embedder")
public class EmbedderProperties {

    /**
     * Provider: {@code openai} (OpenAI-compatible REST) or {@code gemini}. Null/blank -> local
     * stub.
     */
    private @Nullable String provider;

    /** Embedding model name, e.g. {@code text-embedding-3-small} or {@code text-embedding-004}. */
    private @Nullable String model;

    /**
     * Vector dimension the model produces; must match the model. Used for pgvector DDL. Default 64.
     */
    private int dimension = 64;

    /** Base URL override; null/blank uses the provider default. */
    private @Nullable String baseUrl;

    /** Vault secure-note title holding the API key. */
    private @Nullable String credentialKey;

    public @Nullable String getProvider() {
        return provider;
    }

    public void setProvider(@Nullable String provider) {
        this.provider = provider;
    }

    public @Nullable String getModel() {
        return model;
    }

    public void setModel(@Nullable String model) {
        this.model = model;
    }

    public int getDimension() {
        return dimension;
    }

    public void setDimension(int dimension) {
        this.dimension = dimension;
    }

    public @Nullable String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(@Nullable String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public @Nullable String getCredentialKey() {
        return credentialKey;
    }

    public void setCredentialKey(@Nullable String credentialKey) {
        this.credentialKey = credentialKey;
    }
}
