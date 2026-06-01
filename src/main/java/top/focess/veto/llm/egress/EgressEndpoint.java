package top.focess.veto.llm.egress;

/**
 * The effective transport target for a single LLM call: the base URL the SDK should hit and the
 * credential value it should present.
 *
 * <p>In direct mode this is the provider's own URL plus the real API key. In proxy mode it is the
 * local broker URL plus a low-value internal token — the real secret never enters this JVM.
 *
 * @param baseUrl the effective base URL for the call
 * @param apiKey the API key or internal token to use
 */
public record EgressEndpoint(String baseUrl, String apiKey) {
}
