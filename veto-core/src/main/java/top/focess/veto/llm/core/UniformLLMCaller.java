package top.focess.veto.llm.core;

import top.focess.veto.llm.exceptions.LlmException;

/**
 * The main entry point for the Veto agent loop to call an LLM. Implementations resolve credentials,
 * select the right provider, apply retry/backoff, and return a normalized {@link VetoResponse}.
 */
public interface UniformLLMCaller {
    /**
     * Executes a request against the appropriate provider.
     *
     * @param request the standardized LLM request
     * @return the normalized response from the LLM
     * @throws LlmException if the call fails permanently (auth, capability) or exhausts retries.
     */
    VetoResponse call(VetoRequest request);
}
