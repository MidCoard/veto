package top.focess.veto.llm.embedding;

/** Fixed operator-selected model connection settings; never supplied as tool arguments. */
public class EmbeddingProfile {

    /**
     * Provider: {@code openai} (OpenAI-compatible REST) or {@code gemini}. Null/blank -> local
     * implementation.
     */
    private String provider;

    /** Embedding model name, e.g. {@code text-embedding-3-small} or {@code text-embedding-004}. */
    private String model;

    /**
     * Vector dimension the model produces; must match the model. Used for pgvector DDL. Default 64.
     */
    private int dimension = 64;

    /** Base URL override; null/blank uses the provider default. */
    private String baseUrl;

    /** Vault secure-note title holding the API key. */
    private String credentialKey;

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public int getDimension() {
        return dimension;
    }

    public void setDimension(int dimension) {
        this.dimension = dimension;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getCredentialKey() {
        return credentialKey;
    }

    public void setCredentialKey(String credentialKey) {
        this.credentialKey = credentialKey;
    }
}
